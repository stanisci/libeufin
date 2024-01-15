# Update EBICS constants file using latest external code sets files

import requests
from zipfile import ZipFile
from io import BytesIO
import polars as pl

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

# Extract specific code set from XLSX
df = (
    pl.read_excel(file, sheet_name="AllCodeSets")
    .filter(pl.col("Code Set") == "ExternalStatusReason1Code")
    .sort("Code Value")
)

# Write kotlin file
kt = """/*
 * This file is part of LibEuFin.
 * Copyright (C) 2023 Stanisci and Dold.

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

package tech.libeufin.util

enum class ExternalStatusReasonCode(val isoCode: String, val description: String) {"""
for row in df.rows(named=True):
    (value, isoCode, description) = (
        row["Code Value"],
        row["Code Name"],
        row["Code Definition"].split("\n", 1)[0].strip().strip("."),
    )
    kt += f'\n\t{value}("{isoCode}", "{description}"),'


kt += """;

    companion object {
        fun lookup(statusCode: String): ExternalStatusReasonCode? {
            return values().find { it.name == statusCode }
        }
    }
}"""

with open("src/main/kotlin/EbicsCodeSets.kt", "w") as file1:
    file1.write(kt)
