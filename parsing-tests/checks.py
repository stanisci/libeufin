#!/usr/bin/env python3

import json
import os
from deepdiff import DeepDiff
from subprocess import Popen, PIPE


# return dict with parse-result.
def call_parser(xml_file):
    assert os.path.isfile(xml_file)
    xml_file_abs = os.path.abspath(xml_file)
    with Popen([
        "../gradlew",
        "--console=plain",
        "-q",
        "-p",
        "..",
        "nexus:run",
        f"--args=parse-camt {xml_file_abs}"],
        stdout=PIPE
    ) as proc:
        stdout = proc.communicate()[0]
        assert proc.returncode == 0
        return json.loads(stdout)

def get_json_from_disk(json_file):
    json_file_abs = os.path.abspath(json_file)
    with open(json_file_abs) as j:
        return json.load(j)

def test_dashed_subject():
    parsed = call_parser("./samples/camt53_example_dashed_subject.xml")
    expected = get_json_from_disk("./samples/camt53_example_dashed_subject.json")
    assert not DeepDiff(parsed, expected)

def test_camt53_example3():
    parsed = call_parser("./samples/camt53_example3.xml")
    expected = get_json_from_disk("./samples/camt53_example3.json")
    assert not DeepDiff(parsed, expected)
