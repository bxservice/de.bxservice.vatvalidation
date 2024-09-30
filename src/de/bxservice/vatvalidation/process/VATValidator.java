/**********************************************************************
 * Copyright (C) Contributors                                          *
 *                                                                     *
 * This program is free software; you can redistribute it and/or       *
 * modify it under the terms of the GNU General Public License         *
 * as published by the Free Software Foundation; either version 2      *
 * of the License, or (at your option) any later version.              *
 *                                                                     *
 * This program is distributed in the hope that it will be useful,     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of      *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
 * GNU General Public License for more details.                        *
 *                                                                     *
 * You should have received a copy of the GNU General Public License   *
 * along with this program; if not, write to the Free Software         *
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
 * MA 02110-1301, USA.                                                 *
 *                                                                     *
 * Contributors:                                                       *
 * - Diego Ruiz - Bx Service GmbH                                      *
 * - Carlos Ruiz - Bx Service GmbH                                     *
 **********************************************************************/
package de.bxservice.vatvalidation.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MBPartner;
import org.compiere.model.MBPartnerLocation;
import org.compiere.model.MLocation;
import org.compiere.model.MOrgInfo;
import org.compiere.model.MProcessPara;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.Env;
import org.compiere.util.Msg;
import org.compiere.util.Util;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.bxservice.vatvalidation.model.BusinessPartnerUtils;

public class VATValidator extends SvrProcess {

	private final static String CHECK_VATNUMBER_VIES_URL = "https://ec.europa.eu/taxation_customs/vies/rest-api//check-vat-number"; 

	private final static String CHECK_VATNUMBER_EVATR_URL = "https://evatr.bff-online.de/evatrRPC"; 

	private static final String PREFIX_EVATR_ERROR = "BXS_eVatR_Error_";
	private static final String PREFIX_EVATR_RESULT = "BXS_eVatR_Erg_";

	/* VAT Validation Service */
	private String p_BXS_VATValidationService = null;
	/* Update Name */
	private Boolean p_IsUpdateName = false;
	/* Organization */
	private int p_AD_Org_ID = 0;
	/* Partner Location */
	private int p_C_BPartner_Location_ID = 0;

	private MBPartner bPartner;

	@Override
	protected void prepare() {
		for (ProcessInfoParameter para : getParameter()) {
			String name = para.getParameterName();
			switch (name) {
			case "BXS_VATValidationService":
				p_BXS_VATValidationService = para.getParameterAsString();
				break;
			case "IsUpdateName":
				p_IsUpdateName = para.getParameterAsBoolean();
				break;
			case "AD_Org_ID":
				p_AD_Org_ID = para.getParameterAsInt();
				break;
			case "C_BPartner_Location_ID":
				p_C_BPartner_Location_ID = para.getParameterAsInt();
				break;
			default:
				MProcessPara.validateUnknownParameter(getProcessInfo().getAD_Process_ID(), para);
			}
		}
		bPartner = new MBPartner(getCtx(), getRecord_ID(), get_TrxName());
	}

	@Override
	protected String doIt() throws Exception {
		if (!isValidTaxID())
			return "@Error@ @BXS_InvalidTaxID@";

		boolean isValidVAT = validateVATNumber();
		return isValidVAT ? "@BXS_ValidVATNumber@" : "@Error@ @BXS_ErrorVATNumber@";
	}

	/**
	 * A full VAT identifier starts with an (2 letters) country code 
	 * and then has between 2 and 13 characters.
	 * @return true if it is a valid string
	 */
	private boolean isValidTaxID() {
		return !Util.isEmpty(bPartner.getTaxID()) && bPartner.getTaxID().length() > 4;  
	}

	private boolean validateVATNumber() {

		boolean isValidVATNumber = false;
		if (p_BXS_VATValidationService.equals("eVatR")) {
			// Validate using BZSt - eVatR
			String ownVAT = MOrgInfo.get(p_AD_Org_ID).getTaxID();
			String vatNumberToCheck = bPartner.getTaxID();
			String firmenName = bPartner.getName();
			String ort = null;
			String plz = null;
			String strasse = null;
			if (p_C_BPartner_Location_ID > 0) {
				MBPartnerLocation bpl = new MBPartnerLocation(getCtx(), p_C_BPartner_Location_ID, get_TrxName());
				MLocation loc = MLocation.get(bpl.getC_Location_ID());
				ort = loc.getCity();
				plz = loc.getPostal();
				if (!Util.isEmpty(loc.getPostal_Add()))
					plz += "-" + loc.getPostal_Add();
				strasse = "";
				if (!Util.isEmpty(loc.getAddress1()))
					strasse = loc.getAddress1();
				if (!Util.isEmpty(loc.getAddress2()))
					strasse += (Util.isEmpty(strasse) ? "" : ", ") + loc.getAddress2();
				if (!Util.isEmpty(loc.getAddress3()))
					strasse += (Util.isEmpty(strasse) ? "" : ", ") + loc.getAddress3();
				if (!Util.isEmpty(loc.getAddress4()))
					strasse += (Util.isEmpty(strasse) ? "" : ", ") + loc.getAddress4();
				if (!Util.isEmpty(loc.getAddress5()))
					strasse += (Util.isEmpty(strasse) ? "" : ", ") + loc.getAddress5();
			}
			try {
				// Perform the VAT validation request
				String response = validateVATEVATR(ownVAT, vatNumberToCheck, firmenName, ort, plz, strasse);
				// Parse the response and print out the result
				isValidVATNumber = parseResponseEVATR(response);
			} catch (Exception e) {
				e.printStackTrace();
			}

		} else {
			// Validate using VIES
			Response response = getRequestResponseVIES();

			int responseStatus = response.getStatus();
			String responseBody = response.readEntity(String.class);
			JsonObject jsonResponse = getResponseBodyVIES(responseBody);

			if (responseStatus != Status.OK.getStatusCode()) {
				String msg = "@Error@ Business Partner validating " + bPartner.getValue() + " " + responseStatus + " / " + getErrorMessageVIES(jsonResponse);
				throw new AdempiereException(msg);
			}

			isValidVATNumber = getValidFromResponseVIES(jsonResponse);
			if (isValidVATNumber) {
				validateResponseNameVIES(jsonResponse);
			}
		}

		BusinessPartnerUtils.setIsValidVATNumber(bPartner, isValidVATNumber);
		bPartner.saveEx(null);

		return isValidVATNumber;		
	}

	private Response getRequestResponseVIES() {
		Client client = ClientBuilder.newClient();
		Entity<String> payload = Entity.json(getRequestBodyVIES().toString());

		return client.target(CHECK_VATNUMBER_VIES_URL)
				.request(MediaType.APPLICATION_JSON_TYPE)
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.post(payload);
	}

	private JsonObject getRequestBodyVIES() {
		JsonObject json = new JsonObject();
		json.addProperty("countryCode", getCountryCode(bPartner.getTaxID()));
		json.addProperty("vatNumber", getVATNumber(bPartner.getTaxID()));
		return json;
	}

	/**
	 * The first two characters of the tax ID are the
	 * Country code
	 * @return Country code
	 */
	private String getCountryCode(String taxID) {
		return taxID.substring(0, 2);
	}

	private String getVATNumber(String taxID) {
		return taxID.substring(2);
	}

	private JsonObject getResponseBodyVIES(String responseBody) {
		if (!Util.isEmpty(responseBody)) {
			Gson gson = new GsonBuilder().create();
			return gson.fromJson(responseBody, JsonObject.class);
		} else {
			throw new AdempiereException("Unexpected empty response body");
		}
	}

	private boolean getValidFromResponseVIES(JsonObject jsonResponse) {
		return getElement(jsonResponse, "valid").getAsBoolean();
	}

	private JsonElement getElement(JsonObject jsonResponse, String elementName) {
		if (jsonResponse.get(elementName) == null)
			throw new AdempiereException("Unexpected response. Error: " + getErrorMessageVIES(jsonResponse));

		return jsonResponse.get(elementName);
	}

	private void validateResponseNameVIES(JsonObject jsonResponse) {
		String name = getFromResponseVIES("name", jsonResponse);
		if (p_IsUpdateName)
			bPartner.setName(name);
		else
			addLog("@Name@ = " + name);

		String address = getFromResponseVIES("address", jsonResponse);
		if (!Util.isEmpty(address))
			addLog("@Address@ = " + address);
	}

	private String getFromResponseVIES(String field, JsonObject jsonResponse) {
		return getElement(jsonResponse, field).getAsString();
	}

	private String getErrorMessageVIES(JsonObject jsonResponse) {

		StringBuilder errorMessage = new StringBuilder("");
		if (jsonResponse.get("errorWrappers") != null && jsonResponse.get("errorWrappers").getAsJsonArray() != null) {
			jsonResponse.get("errorWrappers").getAsJsonArray().forEach(e -> {
				if (e.isJsonObject()) {
					String message = "";
					if (((JsonObject) e).get("message") != null)
						message = ((JsonObject) e).get("message").getAsString();
					else if (((JsonObject) e).get("error") != null)
						message = ((JsonObject) e).get("error").getAsString();

					errorMessage.append(message);
				}
			});
		}

		return errorMessage.toString(); 
	}

	// 
	/**
	 * Method to validate a VAT number using the BZSt eVatR site
	 * @param ownVAT
	 * @param vatNumberToCheck
	 * @param strasse 
	 * @param plz 
	 * @param ort 
	 * @param firmenName 
	 * @return
	 * @throws Exception
	 */
	public static String validateVATEVATR(String ownVAT, String vatNumberToCheck, String firmenName, String ort, String plz, String strasse) {
		// Construct the full URL with the required parameters
		BufferedReader in = null;
		StringBuilder response;
		try {
			StringBuilder fullUrl = new StringBuilder(CHECK_VATNUMBER_EVATR_URL);
			fullUrl.append("?UstId_1=").append(URLEncoder.encode(ownVAT, "UTF-8"));
			fullUrl.append("&UstId_2=").append(URLEncoder.encode(vatNumberToCheck, "UTF-8"));
			fullUrl.append("&Firmenname=").append(URLEncoder.encode(firmenName, "UTF-8"));
			fullUrl.append("&Ort=");
			if (!Util.isEmpty(ort))
				fullUrl.append(URLEncoder.encode(ort, "UTF-8"));
			fullUrl.append("&PLZ=");
			if (!Util.isEmpty(plz))
				fullUrl.append(URLEncoder.encode(plz, "UTF-8"));
			fullUrl.append("&Strasse=");
			if (!Util.isEmpty(strasse))
				fullUrl.append(URLEncoder.encode(strasse, "UTF-8"));

			URL url = new URL(fullUrl.toString());
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
			String inputLine;
			response = new StringBuilder();
			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			in.close();
		} catch (IOException e) {
			throw new AdempiereException(e);
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException e) {}
			}
		}
		return response.toString();
	}

	/**
	 * Method to parse the XML response from the BZSt eVatR site
	 * @param response
	 * @return
	 */
	public boolean parseResponseEVATR(String response) {
		boolean isValidVATNumber = false;
		try {
			// Use DocumentBuilder to parse the XML response
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			Document document;
			DocumentBuilder builder = factory.newDocumentBuilder();
			document = builder.parse(new java.io.ByteArrayInputStream(response.getBytes("UTF-8")));

			XPathFactory xpathFactory = XPathFactory.newInstance();
			XPath xpath = xpathFactory.newXPath();
			String errorCode = getFieldFromResponseEVATR("ErrorCode", document, xpath);
			String ustId_2 = getFieldFromResponseEVATR("UstId_2", document, xpath);
			// String druck = getFieldFromResponseEVATR("Druck", document, xpath);
			String firmenname = getFieldFromResponseEVATR("Firmenname", document, xpath);
			String erg_Name = getFieldFromResponseEVATR("Erg_Name", document, xpath);
			String ort = getFieldFromResponseEVATR("Ort", document, xpath);
			String erg_Ort = getFieldFromResponseEVATR("Erg_Ort", document, xpath);
			String plz = getFieldFromResponseEVATR("PLZ", document, xpath);
			String erg_PLZ = getFieldFromResponseEVATR("Erg_PLZ", document, xpath);
			String strasse = getFieldFromResponseEVATR("Strasse", document, xpath);
			String erg_Str = getFieldFromResponseEVATR("Erg_Str", document, xpath);
			String gueltig_ab = getFieldFromResponseEVATR("Gueltig_ab", document, xpath);
			String gueltig_bis = getFieldFromResponseEVATR("Gueltig_bis", document, xpath);
			// String datum = getFieldFromResponseEVATR("Datum", document, xpath);
			// String uhrzeit = getFieldFromResponseEVATR("Uhrzeit", document, xpath);

			String errorMsg = Msg.getMsg(Env.getCtx(), PREFIX_EVATR_ERROR+errorCode);
			addLog(ustId_2 + " -> " + errorCode + " = " + errorMsg);

			// addLog("Datum = " + datum + ", Uhrzeit = " + uhrzeit);
			if (!Util.isEmpty(gueltig_ab) || !Util.isEmpty(gueltig_bis))
				addLog("Gueltig_ab = " + gueltig_ab + ", Gueltig_bis = " + gueltig_bis);
			if (!Util.isEmpty(erg_Name))
				addLog("Erg_Name -> " + firmenname + " -> " + Msg.getMsg(Env.getCtx(), PREFIX_EVATR_RESULT+erg_Name));
			if (!Util.isEmpty(erg_Str))
				addLog("Erg_Str -> " + strasse + " -> " + Msg.getMsg(Env.getCtx(), PREFIX_EVATR_RESULT+erg_Str));
			if (!Util.isEmpty(erg_PLZ))
				addLog("Erg_PLZ -> " + plz + " -> " + Msg.getMsg(Env.getCtx(), PREFIX_EVATR_RESULT+erg_PLZ));
			if (!Util.isEmpty(erg_Ort))
				addLog("Erg_Ort -> " + ort + " -> " + Msg.getMsg(Env.getCtx(), PREFIX_EVATR_RESULT+erg_Ort));
			// if (!Util.isEmpty(druck))
				// addLog("Druck = " + druck);

			if ("200".equals(errorCode) || "216".equals(errorCode) || "218".equals(errorCode) || "219".equals(errorCode) || "223".equals(errorCode))
				isValidVATNumber = true;

		} catch (XPathExpressionException | DOMException | ParserConfigurationException | SAXException
				| IOException e) {
			throw new AdempiereException(e);
		}
		return isValidVATNumber;
	}

	private static String getFieldFromResponseEVATR(String field, Document document, XPath xpath) throws XPathExpressionException {
		String retValue = null;
		String expression = "//param[value/array/data/value[string='" + field + "']]/value/array/data/value[2]/string";
		Node node = (Node) xpath.evaluate(expression, document, XPathConstants.NODE);
		if (node != null)
			retValue = node.getTextContent();
		return retValue;
	}

}