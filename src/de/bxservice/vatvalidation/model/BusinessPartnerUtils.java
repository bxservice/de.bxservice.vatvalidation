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
package de.bxservice.vatvalidation.model;

import org.compiere.model.MBPartner;

public class BusinessPartnerUtils {

	private final static String IS_VALID_VAT_COLUMNNAME = "BXS_IsValidVATNumber";

	public static void setIsValidVATNumber(MBPartner bPartner, boolean isValidVAT) {
		bPartner.set_ValueOfColumn(IS_VALID_VAT_COLUMNNAME, isValidVAT);
	}
	
	public static boolean didTaxIDChanged(MBPartner bPartner) {
		return bPartner.is_ValueChanged(MBPartner.COLUMNNAME_TaxID);
	}
}
