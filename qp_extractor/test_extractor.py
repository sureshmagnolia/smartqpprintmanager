"""
Unit tests for the Calicut University Question Paper Extractor.
Tests extraction accuracy, edge cases, and consistency across dataset.
"""

import unittest
from pathlib import Path
from extractor import extract_qp_data, parse_question_paper_data, extract_all_qps_in_folder

QP_FOLDER = Path(r"C:\Users\sures\Downloads\QP")
DONE_FOLDER = Path(r"C:\Users\sures\Downloads\Done-20261101T034135Z-1-001\Done")
FN_FOLDER = Path(r"C:\Users\sures\Downloads\10.07.26FN-20261101T035950Z-1-001\10.07.26FN")

class TestQPExtractor(unittest.TestCase):

    def test_single_file_152087(self):
        """Test user-mentioned file 152087."""
        target = list(QP_FOLDER.glob("152087_*.pdf"))
        self.assertTrue(len(target) > 0, "152087 PDF file not found")
        data = extract_qp_data(target[0])
        
        self.assertEqual(data["qp_code"], "D 152087")
        self.assertEqual(data["syllabus_year"], "2019")
        self.assertEqual(data["course_code"], "BCM 5B 10")
        self.assertIn("CO-OPERATIVE THEORY AND PRACTICE", data["course_name"].upper())
        self.assertIn("B.Com", data["degree_or_subject"])

    def test_folklore_142992(self):
        """Test Malayalam course with ASCII font and English title in parens: MAL4E16—SlLWVSsL¡ (FOLKLORE)."""
        f = FN_FOLDER / "REG_10.07.26_09_30 AM_142992_FOLKLORE--(MAL4 E16).pdf"
        if f.exists():
            d = extract_qp_data(f)
            self.assertEqual(d["qp_code"], "D 142992")
            self.assertEqual(d["course_code"], "MAL4E16")
            self.assertEqual(d["course_name"], "FOLKLORE")
            self.assertEqual(d["syllabus_year"], "2019")

    def test_mcom_finance_elective_143086(self):
        """Test M.Com stream elective course code: MCM 4E (F) 04."""
        f = FN_FOLDER / "REG_10.07.26_09_30 AM_143086_ADVANCED STRATEGIC FINANCIAL MANAGEMENT--(MCM4EF04).pdf"
        if f.exists():
            d = extract_qp_data(f)
            self.assertEqual(d["qp_code"], "D 143086")
            self.assertEqual(d["course_code"], "MCM 4E (F) 04")
            self.assertEqual(d["course_name"], "ADVANCED STRATEGIC FINANCIAL MANAGEMENT")
            self.assertEqual(d["syllabus_year"], "2019")

    def test_zoology_subcode_143266(self):
        """Test Zoology course code with space-separated sub-code: ZOL 4E 11 01."""
        f = FN_FOLDER / "REG_10.07.26_09_30 AM_143266_ENTOMOLOGY II _ ANATOMY AND PHYSIOLOGY--(ZOL4E1101).pdf"
        if f.exists():
            d = extract_qp_data(f)
            self.assertEqual(d["qp_code"], "D 143266")
            self.assertEqual(d["course_code"], "ZOL 4E 11 01")
            self.assertIn("ENTOMOLOGY", d["course_name"])
            self.assertEqual(d["syllabus_year"], "2019")

    def test_malayalam_electives_131794_131795(self):
        """Test elective codes MAL3E (3) and MAL3E (4)."""
        f1 = DONE_FOLDER / "REG_12.01.26_01_30 PM_131794_FICTIONMAL3 E03.pdf"
        f2 = DONE_FOLDER / "REG_12.01.26_01_30 PM_131795_ECOFEMINIST DALIT LITERATUREMAL3 E04.pdf"
        if f1.exists():
            d1 = extract_qp_data(f1)
            self.assertEqual(d1["qp_code"], "D 131794")
            self.assertEqual(d1["course_code"], "MAL3E (3)")
            self.assertEqual(d1["course_name"], "FICTION")
            self.assertEqual(d1["syllabus_year"], "2019")
        if f2.exists():
            d2 = extract_qp_data(f2)
            self.assertEqual(d2["qp_code"], "D 131795")
            self.assertEqual(d2["course_code"], "MAL3E (4)")
            self.assertEqual(d2["course_name"], "ECO-FEMINIST-DALIT LITERATURE")
            self.assertEqual(d2["syllabus_year"], "2019")

    def test_sde_suffix_codes(self):
        """Test SDE question paper codes with 'A' suffix (e.g., D 131729A)."""
        f = DONE_FOLDER / "SDE_12.01.26_01_30 PM_131729A_BASIC ECONOMETRICS_ECO3C11.pdf"
        if f.exists():
            d = extract_qp_data(f)
            self.assertEqual(d["qp_code"], "D 131729A")
            self.assertEqual(d["course_code"], "ECO 3C 11")
            self.assertEqual(d["course_name"], "BASIC ECONOMETRICS")
            self.assertEqual(d["syllabus_year"], "2019")

    def test_multi_line_course_153797(self):
        """Test course name appearing on the subsequent line."""
        target = list(QP_FOLDER.glob("153797_*.pdf"))
        self.assertTrue(len(target) > 0)
        data = extract_qp_data(target[0])
        
        self.assertEqual(data["qp_code"], "D 153797")
        self.assertEqual(data["syllabus_year"], "2024")
        self.assertEqual(data["course_code"], "MAT5EJ301(1)")
        self.assertEqual(data["course_name"], "Mathematical Foundations of Computing")

    def test_vector_ocr_fallback_153668(self):
        """Test vector-outlined PDF requiring OCR fallback."""
        target = list(QP_FOLDER.glob("153668_*.pdf"))
        self.assertTrue(len(target) > 0)
        data = extract_qp_data(target[0])
        
        self.assertEqual(data["qp_code"], "D 153668")
        self.assertEqual(data["syllabus_year"], "2024")
        self.assertEqual(data["course_code"], "GEO 5EJ305(1)")
        self.assertIn("WORLD REGIONAL GEOGRAPHY", data["course_name"])

    def test_all_dataset_coverage(self):
        """Ensure 100% of PDFs have QP code, course name, and syllabus year."""
        results = extract_all_qps_in_folder(QP_FOLDER)
        self.assertGreaterEqual(len(results), 45)

        for res in results:
            with self.subTest(file=res["filename"]):
                self.assertIsNotNone(res["qp_code"], f"Missing QP Code for {res['filename']}")
                self.assertTrue(res["qp_code"].startswith("D "), f"Invalid QP Code format: {res['qp_code']}")
                self.assertIsNotNone(res["syllabus_year"], f"Missing Syllabus Year for {res['filename']}")
                self.assertIsNotNone(res["course_name"], f"Missing Course Name for {res['filename']}")
                self.assertIsNotNone(res["course_code"], f"Missing Course Code for {res['filename']}")

if __name__ == "__main__":
    unittest.main()

