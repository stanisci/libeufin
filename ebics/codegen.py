# Update EBICS constants file using latest external code sets files

import polars as pl
import requests
from io import BytesIO
from zipfile import ZipFile


def iso20022codegen():
    # Get XLSX zip file from server
    r = requests.get(
        "https://www.iso20022.org/sites/default/files/media/file/ExternalCodeSets_XLSX.zip"
    )
    assert r.status_code == 200

    # Unzip the XLSX file
    zip = ZipFile(BytesIO(r.content))
    files = zip.namelist()
    assert len(files) == 1
    file = zip.open(files[0])

    # Parse excel
    df = pl.read_excel(file, sheet_name="AllCodeSets")

    def extractCodeSet(setName: str, className: str) -> str:
        out = f"enum class {className}(val isoCode: String, val description: String) {{"

        for row in df.filter(pl.col("Code Set") == setName).sort("Code Value").rows(named=True):
            (value, isoCode, description) = (
                row["Code Value"],
                row["Code Name"],
                row["Code Definition"].split("\n", 1)[0].strip(),
            )
            out += f'\n\t{value}("{isoCode}", "{description}"),'

        out += "\n}"
        return out

    # Write kotlin file
    kt = f"""/*
 * This file is part of LibEuFin.
 * Copyright (C) 2024 Taler Systems S.A.

 * LibEuFin is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3, or
 * (at your option) any later version.

 * LibEuFin is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General
 * Public License for more details.

 * You should have received a copy of the GNU Affero General Public
 * License along with LibEuFin; see the file COPYING.  If not, see
 * <http://www.gnu.org/licenses/>
 */

// THIS FILE IS GENERATED, DO NOT EDIT

package tech.libeufin.ebics

{extractCodeSet("ExternalStatusReason1Code", "ExternalStatusReasonCode")}

{extractCodeSet("ExternalPaymentGroupStatus1Code", "ExternalPaymentGroupStatusCode")}

{extractCodeSet("ExternalPaymentTransactionStatus1Code", "ExternalPaymentTransactionStatusCode")}
"""
    with open("src/main/kotlin/Iso20022CodeSets.kt", "w") as file1:
        file1.write(kt)

iso20022codegen()
