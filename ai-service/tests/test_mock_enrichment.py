import unittest

from src.main import EnrichmentRequest, mock_suggestions


class MockEnrichmentTest(unittest.TestCase):
    def test_sparse_sustainable_input_returns_valid_sense_suggestion(self):
        result = mock_suggestions(EnrichmentRequest(lemma="sustainable", explanation_vi="bền vững"))
        self.assertFalse(result.ambiguous)
        self.assertEqual("ADJECTIVE", result.suggestions[0].part_of_speech)
        self.assertEqual("B2", result.suggestions[0].cefr_level)
        self.assertTrue(result.suggestions[0].definition_en)

    def test_unresolved_bank_is_not_collapsed_to_one_sense(self):
        result = mock_suggestions(EnrichmentRequest(lemma="bank"))
        self.assertTrue(result.ambiguous)
        self.assertEqual({"NOUN", "VERB"}, {item.part_of_speech for item in result.suggestions})
        self.assertEqual(3, len(result.suggestions))


if __name__ == "__main__":
    unittest.main()
