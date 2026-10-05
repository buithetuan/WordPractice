import os

from fastapi import FastAPI

app = FastAPI(title="WordPractice AI Service", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "provider": "MOCKED"}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("src.main:app", host="0.0.0.0", port=int(os.getenv("AI_SERVICE_PORT", "8000")))
