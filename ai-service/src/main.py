import os

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="WordPractice AI Service", version="0.1.0")


class ExistingSense(BaseModel):
    part_of_speech: str
    definition_en: str | None = None
    explanation_vi: str | None = None


class EnrichmentRequest(BaseModel):
    lemma: str = Field(min_length=1, max_length=200)
    part_of_speech: str | None = None
    definition_en: str | None = None
    explanation_vi: str | None = None
    existing_senses: list[ExistingSense] = Field(default_factory=list)


class SenseSuggestion(BaseModel):
    part_of_speech: str
    definition_en: str | None = None
    explanation_vi: str | None = None
    cefr_level: str | None = None
    topic: str | None = None
    examples: list[str] = Field(default_factory=list)
    related_words: list[str] = Field(default_factory=list)


class EnrichmentResponse(BaseModel):
    provider: str
    model: str
    ambiguous: bool
    suggestions: list[SenseSuggestion]


def mock_suggestions(request: EnrichmentRequest) -> EnrichmentResponse:
    lemma = request.lemma.strip().casefold()
    if lemma == "bank" and not request.part_of_speech:
        return EnrichmentResponse(
            provider="MOCK",
            model="deterministic-fixture-v1",
            ambiguous=True,
            suggestions=[
                SenseSuggestion(part_of_speech="NOUN", definition_en="A financial institution that keeps and lends money.", explanation_vi="Ngân hàng; tổ chức nhận tiền gửi và cho vay."),
                SenseSuggestion(part_of_speech="NOUN", definition_en="The land alongside or sloping down to a river.", explanation_vi="Bờ sông."),
                SenseSuggestion(part_of_speech="VERB", definition_en="To rely on or expect something.", explanation_vi="Dựa vào hoặc trông cậy vào."),
            ],
        )

    if lemma == "sustainable":
        suggestion = SenseSuggestion(
            part_of_speech=request.part_of_speech or "ADJECTIVE",
            definition_en=request.definition_en or "Able to continue over a period of time without causing lasting harm or using up resources.",
            explanation_vi=request.explanation_vi or "Có thể duy trì lâu dài mà không làm cạn kiệt tài nguyên hoặc gây tổn hại lâu dài.",
            cefr_level="B2",
            topic="environment",
            examples=["The city is investing in sustainable public transport."],
            related_words=["renewable", "viable"],
        )
    else:
        suggestion = SenseSuggestion(
            part_of_speech=request.part_of_speech or "NOUN",
            definition_en=request.definition_en,
            explanation_vi=request.explanation_vi,
            cefr_level="B1",
            topic=None,
            examples=[],
            related_words=[],
        )

    return EnrichmentResponse(
        provider="MOCK",
        model="deterministic-fixture-v1",
        ambiguous=not request.part_of_speech and not request.definition_en and len(request.existing_senses) > 1,
        suggestions=[suggestion],
    )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "provider": "MOCKED"}


@app.post("/v1/enrichment/suggestions", response_model=EnrichmentResponse)
def enrichment_suggestions(request: EnrichmentRequest) -> EnrichmentResponse:
    try:
        return mock_suggestions(request)
    except Exception as exc:  # The API returns structured errors instead of partial lexical data.
        raise HTTPException(status_code=422, detail="Could not create a valid enrichment suggestion") from exc


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("src.main:app", host="0.0.0.0", port=int(os.getenv("AI_SERVICE_PORT", "8000")))
