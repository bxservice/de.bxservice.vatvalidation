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
 **********************************************************************/
package de.bxservice.viesvatvalidation.process;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MBPartner;
import org.compiere.model.MBPartnerLocation;
import org.compiere.model.MCity;
import org.compiere.model.MClient;
import org.compiere.model.MLocation;
import org.compiere.model.MProcessPara;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Language;
import org.compiere.util.Util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import de.bxservice.viesvatvalidation.model.BusinessPartnerUtils;

public class ViesVATValidator extends SvrProcess {

	private final static String CHECK_VATNUMBER_URL = "https://ec.europa.eu/taxation_customs/vies/rest-api//check-vat-number"; 

	private MBPartner bPartner;
	private boolean isUpdateName = false;
	private boolean isUpdateAddress = false;

	@Override
	protected void prepare() {
		for (ProcessInfoParameter para : getParameter()) {
			String name = para.getParameterName();
			switch (name) {
			case "IsUpdateName":
				isUpdateName = para.getParameterAsBoolean();
			case "IsUpdateAddress":
				isUpdateAddress = para.getParameterAsBoolean();
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
		Response response = getRequestResponse();

		int responseStatus = response.getStatus();
		String responseBody = response.readEntity(String.class);
		JsonObject jsonResponse = getResponseBody(responseBody);
		
		if (responseStatus != Status.OK.getStatusCode()) {
			String msg = "@Error@ Business Partner validating " + bPartner.getValue() + " " + responseStatus + " / " + getErrorMessage(jsonResponse);
			throw new AdempiereException(msg);
		}
		
		boolean isValidVATNumber = getValidFromResponse(jsonResponse);
		BusinessPartnerUtils.setIsValidVATNumber(bPartner, isValidVATNumber);
		if (isValidVATNumber) {
			validateResponseName(jsonResponse);
			validateResponseAddress(jsonResponse);
		}
		bPartner.saveEx(null);

		return isValidVATNumber;		
	}

	private Response getRequestResponse() {
		Client client = ClientBuilder.newClient();
		Entity<String> payload = Entity.json(getRequestBody().toString());

		return client.target(CHECK_VATNUMBER_URL)
				.request(MediaType.APPLICATION_JSON_TYPE)
				.header("Accept", "application/json")
				.header("Content-Type", "application/json")
				.post(payload);
	}

	private JsonObject getRequestBody() {
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
		if(taxID.substring(0, 2).chars().allMatch(Character::isLetter)) {
			return taxID.substring(0, 2);
		}
		MClient client = new MClient(Env.getCtx(), Env.getAD_Client_ID(Env.getCtx()), get_TrxName());
		Language language = client.getLanguage();
		return language.getLanguageCode();
	}

	private String getVATNumber(String taxID) {
		if(taxID.substring(0, 2).chars().allMatch(Character::isLetter)) {
			return taxID.substring(2);
		}
		return taxID;
	}

	private JsonObject getResponseBody(String responseBody) {
		if (!Util.isEmpty(responseBody)) {
			Gson gson = new GsonBuilder().create();
			return gson.fromJson(responseBody, JsonObject.class);
		} else {
			throw new AdempiereException("Unexpected empty response body");
		}
	}
	
	private boolean getValidFromResponse(JsonObject jsonResponse) {
		return getElement(jsonResponse, "valid").getAsBoolean();
	}
	
	private JsonElement getElement(JsonObject jsonResponse, String elementName) {
		if (jsonResponse.get(elementName) == null)
			throw new AdempiereException("Unexpected response. Error: " + getErrorMessage(jsonResponse));
		
		return jsonResponse.get(elementName);
	}
	
	private void validateResponseName(JsonObject jsonResponse) {
		String name = getNameFromResponse(jsonResponse);

		if (isUpdateName)
			bPartner.setName(name);
		else
			addLog("@Name@ = " + name);
	}
	
	private void validateResponseAddress(JsonObject jsonResponse) {
		if(!isUpdateAddress) {
			return;
		}
		
		String address = getAddressFromResponse(jsonResponse);
		
		String [] parts = address.split("\n");
		
		if(parts.length < 2) {
			return;
		}
	
		String part2 = parts[1].trim();
		
		String address1 = address.split("\n")[0].trim();
		String postal = part2.substring(0,5);
		String locode = part2.substring(part2.length() - 2);
		String city = part2.substring(6, part2.length() - 3);
		int c_City_ID = getC_City_ID(city);
		
		MLocation location = new MLocation(Env.getCtx(), 0, get_TrxName());
		location.setAddress1(address1);
		location.setPostal(postal);
		location.set_ValueOfColumn("Locode", locode);
		location.setC_City_ID(c_City_ID);
		location.setCity(city);
		location.saveEx(get_TrxName());
		
		MBPartnerLocation bplocation = new MBPartnerLocation(bPartner);
		bplocation.setName(city);
		bplocation.setC_Location_ID(location.getC_Location_ID());
		bplocation.setIsShipTo(true);
		bplocation.setIsBillTo(true);
		bplocation.setIsPayFrom(true);
		bplocation.setIsRemitTo(true);
		bplocation.saveEx(get_TrxName());
	}
	
	private String getNameFromResponse(JsonObject jsonResponse) {
		return getElement(jsonResponse, "name").getAsString();
	}
	
	private String getAddressFromResponse(JsonObject jsonResponse) {
		return getElement(jsonResponse, "address").getAsString();
	}
	
	private String getErrorMessage(JsonObject jsonResponse) {
		
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
	
	private int getC_City_ID(String city) {
		String sql = "SELECT C_City_ID FROM C_City WHERE UPPER(name) = UPPER(?)";
		return DB.getSQLValue(get_TrxName(), sql, city);
	}
}
