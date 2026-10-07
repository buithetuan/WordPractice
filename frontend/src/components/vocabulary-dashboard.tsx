"use client";

import { FormEvent, useEffect, useState } from "react";

type Sense = {
  id: string;
  partOfSpeech: string;
  order: number;
  definitionEn: string | null;
  explanationVi: string | null;
  cefrLevel: string | null;
  topic: string | null;
  examples: { id: string; sentenceEn: string; translationVi: string | null }[];
};
type Vocabulary = { id: string; lemma: string; language: string; senses?: Sense[] };
type Deck = { id: string; name: string; description: string | null; visibility: string; itemCount: number };
type DeckItem = { id: string; vocabularyId: string; vocabularySenseId: string | null; lemma: string; definitionEn: string | null };
type ImportPreview = {
  jobId: string;
  filename: string;
  detectedEncoding: string;
  encodingRepairProposed: boolean;
  columns: { field: string; columnIndex: number; header: string; confidence: number; reviewRequired: boolean }[];
  rows: { rowNumber: number; values: Record<string, string>; status: string; issues: string[]; repairSuggestions: Record<string, string> }[];
  validCount: number;
  ambiguousCount: number;
  invalidCount: number;
};
type Enrichment = {
  jobId: string;
  provider: string;
  model: string;
  ambiguous: boolean;
  suggestions: { partOfSpeech: string; definitionEn: string | null; explanationVi: string | null; cefrLevel: string | null; topic: string | null; examples: string[]; relatedWords: string[] }[];
};

const fields = ["WORD", "IPA", "MEANING_VI", "DEFINITION_EN", "POS", "EXAMPLE", "TOPIC"];

async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/backend/${path}`, { ...init, cache: "no-store" });
  if (!response.ok) {
    const data = await response.json().catch(() => ({ error: `Request failed (${response.status})` }));
    throw new Error(data.error ?? data.message ?? `Request failed (${response.status})`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

function json(body: unknown): RequestInit {
  return { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

export default function VocabularyDashboard() {
  const [tab, setTab] = useState<"words" | "sets" | "import">("words");
  const [words, setWords] = useState<Vocabulary[]>([]);
  const [sets, setSets] = useState<Deck[]>([]);
  const [selectedWord, setSelectedWord] = useState<Vocabulary | null>(null);
  const [selectedSet, setSelectedSet] = useState<string>("");
  const [setItems, setSetItems] = useState<DeckItem[]>([]);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [mapping, setMapping] = useState<Record<string, number>>({});
  const [posOverrides, setPosOverrides] = useState<Record<number, string>>({});
  const [acceptedFields, setAcceptedFields] = useState<string[]>(["DEFINITION_EN", "EXPLANATION_VI"]);
  const [enrichment, setEnrichment] = useState<Enrichment | null>(null);
  const [suggestionIndex, setSuggestionIndex] = useState(0);
  const [acceptRepairs, setAcceptRepairs] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function refreshWords(query = "") {
    setWords(await api<Vocabulary[]>(`v1/vocabularies${query ? `?q=${encodeURIComponent(query)}` : ""}`));
  }
  async function refreshSets() {
    const result = await api<Deck[]>("v1/vocabulary-sets");
    setSets(result);
    if (!selectedSet && result.length) setSelectedSet(result[0].id);
  }

  useEffect(() => {
    void Promise.all([refreshWords(), refreshSets()]).catch(caught => setError((caught as Error).message));
  }, []);

  useEffect(() => {
    if (!selectedSet) { setSetItems([]); return; }
    void api<DeckItem[]>(`v1/vocabulary-sets/${selectedSet}/items`)
      .then(setSetItems).catch(caught => setError((caught as Error).message));
  }, [selectedSet]);

  async function runAction(action: () => Promise<void>, success?: string) {
    setBusy(true); setError(""); setMessage("");
    try { await action(); if (success) setMessage(success); }
    catch (caught) { setError((caught as Error).message); }
    finally { setBusy(false); }
  }

  async function createVocabulary(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    await runAction(async () => {
      const created = await api<Vocabulary>("v1/vocabularies", json({
        language: "en", lemma: String(form.get("lemma")),
        senses: [{ partOfSpeech: String(form.get("pos")), definitionEn: String(form.get("definition") || ""),
          explanationVi: String(form.get("meaning") || ""), cefrLevel: String(form.get("cefr") || ""), examples: [] }],
      }));
      formElement.reset();
      await refreshWords();
      await openWord(created.id);
    }, "Đã lưu từ vựng và nghĩa.");
  }

  async function openWord(id: string) {
    const detail = await api<Vocabulary>(`v1/vocabularies/${id}`);
    setSelectedWord(detail);
    setEnrichment(null);
  }

  async function createSet(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    await runAction(async () => {
      const result = await api<{ id: string }>("v1/vocabulary-sets", json({ name: String(form.get("name")), description: String(form.get("description") || "") }));
      formElement.reset();
      await refreshSets();
      setSelectedSet(result.id);
    }, "Đã tạo bộ từ riêng.");
  }

  async function addSelectedWord() {
    if (!selectedSet || !selectedWord) return;
    await runAction(async () => {
      await api(`v1/vocabulary-sets/${selectedSet}/items`, json({ vocabularyId: selectedWord.id }));
      setSetItems(await api<DeckItem[]>(`v1/vocabulary-sets/${selectedSet}/items`));
      await refreshSets();
    }, "Đã thêm từ vào bộ từ.");
  }

  async function previewImport(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const file = form.get("file");
    if (!(file instanceof File) || file.size === 0) { setError("Chọn file CSV hoặc XLSX trước."); return; }
    await runAction(async () => {
      const query = selectedSet ? `?targetSetId=${encodeURIComponent(selectedSet)}` : "";
      const result = await api<ImportPreview>(`v1/imports/preview${query}`, { method: "POST", body: form });
      setPreview(result);
      const detected: Record<string, number> = {};
      for (const column of result.columns) if (column.field !== "IGNORE") detected[column.field] = column.columnIndex;
      setMapping(detected);
      setPosOverrides({});
      setAcceptRepairs(false);
    }, "Đã tạo bản xem trước. Chưa có từ nào được lưu.");
  }

  async function confirmImport() {
    if (!preview) return;
    await runAction(async () => {
      const result = await api<{ vocabularySetId: string; importedCount: number; rejectedCount: number; status: string }>(
        `v1/imports/${preview.jobId}/confirm`, json({ mapping, acceptEncodingRepairs: acceptRepairs, rowPartOfSpeechOverrides: posOverrides }));
      if (result.vocabularySetId) setSelectedSet(result.vocabularySetId);
      setMessage(`Import xong: ${result.importedCount} dòng đã lưu, ${result.rejectedCount} dòng cần xem lại.`);
      setPreview(null);
      await Promise.all([refreshWords(), refreshSets()]);
    });
  }

  async function suggestEnrichment(senseId?: string) {
    if (!selectedWord) return;
    await runAction(async () => {
      const result = await api<Enrichment>("v1/enrichment/suggestions", json({ vocabularyId: selectedWord.id, vocabularySenseId: senseId }));
      setEnrichment(result);
      setSuggestionIndex(0);
      setAcceptedFields(["DEFINITION_EN", "EXPLANATION_VI", "CEFR_LEVEL", "TOPIC", "EXAMPLES"]);
    }, "Gợi ý được lưu để xem xét; chưa ghi vào nghĩa từ.");
  }

  async function applyEnrichment() {
    if (!enrichment) return;
    await runAction(async () => {
      await api(`v1/enrichment/${enrichment.jobId}/apply`, json({ acceptedFields, selectedSuggestionIndex: suggestionIndex }));
      await openWord(selectedWord!.id);
      setEnrichment(null);
    }, "Đã áp dụng các trường được chọn.");
  }

  async function savePronunciation(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedWord) return;
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    await runAction(async () => {
      const file = form.get("audio");
      const accent = String(form.get("accent"));
      const vocabularySenseId = String(form.get("senseId") || "");
      if (file instanceof File && file.size) {
        const audio = new FormData(); audio.set("file", file); audio.set("accent", accent);
        audio.set("ipa", String(form.get("ipa") || "")); audio.set("stressPattern", String(form.get("stress") || ""));
        if (vocabularySenseId) audio.set("vocabularySenseId", vocabularySenseId);
        await api(`v1/vocabularies/${selectedWord.id}/pronunciations/audio`, { method: "POST", body: audio });
      } else {
        await api(`v1/vocabularies/${selectedWord.id}/pronunciations`, json({
          vocabularySenseId: vocabularySenseId || null, accent, ipa: String(form.get("ipa") || ""), stressPattern: String(form.get("stress") || ""),
        }));
      }
      await openWord(selectedWord.id);
      formElement.reset();
    }, "Đã lưu phát âm.");
  }

  return (
    <div className="workspace">
      <div className="status-line" aria-live="polite">{error && <span className="error">{error}</span>}{message && <span className="success">{message}</span>}</div>
      <nav className="tabs" aria-label="Chức năng từ vựng">
        <button className={tab === "words" ? "active" : ""} onClick={() => setTab("words")}>Từ vựng</button>
        <button className={tab === "sets" ? "active" : ""} onClick={() => setTab("sets")}>Bộ từ</button>
        <button className={tab === "import" ? "active" : ""} onClick={() => setTab("import")}>Nhập danh sách</button>
      </nav>

      {tab === "words" && <div className="columns">
        <section className="panel">
          <h2>Thêm từ vựng</h2>
          <form className="form" onSubmit={createVocabulary}>
            <label>Từ <input name="lemma" required maxLength={200} placeholder="sustainable" /></label>
            <label>Từ loại <select name="pos"><option value="NOUN">Danh từ</option><option value="VERB">Động từ</option><option value="ADJECTIVE">Tính từ</option><option value="ADVERB">Trạng từ</option><option value="PHRASE">Cụm từ</option></select></label>
            <label>Định nghĩa tiếng Anh <textarea name="definition" rows={2} /></label>
            <label>Nghĩa tiếng Việt <textarea name="meaning" rows={2} /></label>
            <label>CEFR <select name="cefr"><option value="">Chưa xác định</option>{["A1", "A2", "B1", "B2", "C1", "C2"].map(level => <option key={level}>{level}</option>)}</select></label>
            <button disabled={busy}>Lưu từ</button>
          </form>
          <h2 className="section-title">Danh mục ({words.length})</h2>
          <div className="list">
            {words.map(word => <button key={word.id} className={selectedWord?.id === word.id ? "list-row selected" : "list-row"} onClick={() => void openWord(word.id)}>
              <strong>{word.lemma}</strong><span>{word.language}</span>
            </button>)}
            {!words.length && <p className="muted">Chưa có từ. Hãy thêm từ hoặc nhập danh sách.</p>}
          </div>
        </section>

        <section className="panel detail-panel">
          {!selectedWord ? <div className="empty"><h2>Chi tiết từ</h2><p>Chọn một từ trong danh mục để xem nghĩa, phát âm hoặc xin gợi ý enrichment.</p></div> : <>
            <div className="detail-heading"><div><span className="eyebrow">{selectedWord.language.toUpperCase()}</span><h2>{selectedWord.lemma}</h2></div><button className="secondary" onClick={() => void suggestEnrichment()}>Gợi ý bổ sung</button></div>
            {(selectedWord.senses ?? []).map((sense, index) => <article className="sense" key={sense.id}>
              <div className="sense-heading"><span>{index + 1}. {sense.partOfSpeech}</span><span>{sense.cefrLevel ?? "CEFR —"}</span><button className="text-button" onClick={() => void suggestEnrichment(sense.id)}>Bổ sung nghĩa này</button></div>
              {sense.definitionEn && <p>{sense.definitionEn}</p>}{sense.explanationVi && <p className="vietnamese">{sense.explanationVi}</p>}
              {sense.topic && <span className="chip">{sense.topic}</span>}
              {sense.examples.map(example => <blockquote key={example.id}>{example.sentenceEn}{example.translationVi && <small>{example.translationVi}</small>}</blockquote>)}
            </article>)}
            {!!selectedWord.senses?.length && <form className="pronunciation-form" onSubmit={savePronunciation}>
              <h3>Phát âm</h3>
              <div className="form-grid"><select name="accent"><option value="UK">UK</option><option value="US">US</option></select>
                <select name="senseId"><option value="">Toàn từ</option>{selectedWord.senses.map(sense => <option key={sense.id} value={sense.id}>{sense.partOfSpeech}: {sense.definitionEn || sense.explanationVi || `Nghĩa ${sense.order}`}</option>)}</select>
              </div>
              <label>IPA <input name="ipa" placeholder="/səˈsteɪnəbəl/" /></label>
              <label>Trọng âm <input name="stress" placeholder="sustain·a·ble" /></label>
              <label>Audio MP3/WAV/OGG/WebM <input name="audio" type="file" accept="audio/mpeg,audio/wav,audio/ogg,audio/webm" /></label>
              <button className="secondary" disabled={busy}>Lưu phát âm</button>
              {selectedWord.senses.length > 0 && <PronunciationList word={selectedWord} />}
            </form>}
            {enrichment && <div className="suggestion-box">
              <div className="detail-heading"><h3>Gợi ý ({enrichment.provider})</h3><button className="text-button" onClick={() => setEnrichment(null)}>Đóng</button></div>
              {enrichment.ambiguous && <p className="warning">Từ này có nhiều nghĩa. Chọn nghĩa muốn áp dụng.</p>}
              {enrichment.suggestions.map((suggestion, index) => <label className="suggestion-choice" key={`${suggestion.partOfSpeech}-${index}`}>
                <input type="radio" name="suggestion" checked={suggestionIndex === index} onChange={() => setSuggestionIndex(index)} />
                <span><strong>{suggestion.partOfSpeech}</strong>{suggestion.definitionEn && <span>{suggestion.definitionEn}</span>}{suggestion.explanationVi && <span className="vietnamese">{suggestion.explanationVi}</span>}</span>
              </label>)}
              <div className="checks">{[["DEFINITION_EN", "Định nghĩa EN"], ["EXPLANATION_VI", "Giải nghĩa VI"], ["CEFR_LEVEL", "CEFR"], ["TOPIC", "Chủ đề"], ["EXAMPLES", "Ví dụ"], ["RELATED_WORDS", "Từ liên quan"]].map(([key, label]) => <label key={key}><input type="checkbox" checked={acceptedFields.includes(key)} onChange={event => setAcceptedFields(old => event.target.checked ? [...old, key] : old.filter(item => item !== key))} />{label}</label>)}</div>
              <button disabled={busy || acceptedFields.length === 0} onClick={() => void applyEnrichment()}>Áp dụng trường đã chọn</button>
            </div>}
          </>}
        </section>
      </div>}

      {tab === "sets" && <div className="columns">
        <section className="panel"><h2>Tạo bộ từ riêng</h2>
          <form className="form" onSubmit={createSet}><label>Tên bộ từ <input name="name" required maxLength={200} /></label><label>Mô tả <textarea name="description" rows={2} /></label><button disabled={busy}>Tạo bộ từ</button></form>
          <h2 className="section-title">Bộ từ của tôi</h2>
          <div className="list">{sets.map(deck => <button key={deck.id} className={selectedSet === deck.id ? "list-row selected" : "list-row"} onClick={() => setSelectedSet(deck.id)}><strong>{deck.name}</strong><span>{deck.itemCount} từ · {deck.visibility}</span></button>)}</div>
        </section>
        <section className="panel"><div className="detail-heading"><div><span className="eyebrow">BỘ TỪ</span><h2>{sets.find(deck => deck.id === selectedSet)?.name ?? "Chọn bộ từ"}</h2></div>{selectedSet && <button className="danger" disabled={busy} onClick={() => void runAction(async () => { await api(`v1/vocabulary-sets/${selectedSet}`, { method: "DELETE" }); setSelectedSet(""); await refreshSets(); }, "Đã chuyển bộ từ vào thùng xóa mềm.")}>Xóa bộ từ</button>}</div>
          <label>Chọn từ để thêm <select value={selectedWord?.id ?? ""} onChange={event => { const word = words.find(item => item.id === event.target.value); if (word) void openWord(word.id); }}><option value="">Chọn từ...</option>{words.map(word => <option key={word.id} value={word.id}>{word.lemma}</option>)}</select></label>
          <button className="secondary" disabled={!selectedSet || !selectedWord || busy} onClick={() => void addSelectedWord()}>Thêm từ đang chọn</button>
          <h3 className="section-title">Từ trong bộ ({setItems.length})</h3>
          <div className="list">{setItems.map(item => <div className="list-row" key={item.id}><div><strong>{item.lemma}</strong>{item.definitionEn && <small>{item.definitionEn}</small>}</div><button className="text-button" onClick={() => void runAction(async () => { await api(`v1/vocabulary-sets/items/${item.id}`, { method: "DELETE" }); setSetItems(await api<DeckItem[]>(`v1/vocabulary-sets/${selectedSet}/items`)); await refreshSets(); })}>Gỡ</button></div>)}</div>
        </section>
      </div>}

      {tab === "import" && <section className="panel import-panel"><h2>Nhập CSV hoặc Excel</h2>
        <p className="muted">Tạo bản xem trước trước khi lưu. Kiểm tra mapping, mã hóa và các dòng có nghĩa/từ loại chưa rõ.</p>
        <form className="upload-form" onSubmit={previewImport}><input name="file" type="file" accept=".csv,.xlsx,.xls,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" required /><button disabled={busy}>Tạo bản xem trước</button></form>
        {preview && <div className="preview">
          <div className="detail-heading"><div><span className="eyebrow">BẢN XEM TRƯỚC</span><h3>{preview.filename}</h3><p className="muted">Encoding {preview.detectedEncoding} · {preview.validCount} hợp lệ · {preview.ambiguousCount} cần xem · {preview.invalidCount} lỗi</p></div></div>
          <div className="mapping-grid">{fields.map(field => <label key={field}>{field}<select value={mapping[field] ?? -1} onChange={event => setMapping(old => { const next = { ...old }; const index = Number(event.target.value); if (index < 0) delete next[field]; else next[field] = index; return next; })}><option value={-1}>Bỏ qua</option>{preview.columns.map(column => <option key={column.columnIndex} value={column.columnIndex}>{column.columnIndex + 1}. {column.header || `Cột ${column.columnIndex + 1}`} · {column.field} ({Math.round(column.confidence * 100)}%)</option>)}</select></label>)}</div>
          <div className="table-scroll"><table><thead><tr><th>Dòng</th><th>Từ</th><th>Nghĩa VI</th><th>Từ loại</th><th>Trạng thái</th><th>Ghi chú</th></tr></thead><tbody>{preview.rows.map(row => <tr key={row.rowNumber}><td>{row.rowNumber}</td><td>{row.values.WORD}{row.repairSuggestions[String(mapping.WORD)] && <small className="repair">Gợi ý: {row.repairSuggestions[String(mapping.WORD)]}</small>}</td><td>{row.values.MEANING_VI || row.values.DEFINITION_EN}</td><td>{row.values.POS || <select aria-label={`Từ loại dòng ${row.rowNumber}`} value={posOverrides[row.rowNumber] || ""} onChange={event => setPosOverrides(old => ({ ...old, [row.rowNumber]: event.target.value }))}><option value="">Chọn POS…</option><option value="NOUN">NOUN</option><option value="VERB">VERB</option><option value="ADJECTIVE">ADJECTIVE</option><option value="ADVERB">ADVERB</option><option value="PHRASE">PHRASE</option></select>}</td><td><span className={`tag ${row.status.toLowerCase()}`}>{row.status}</span></td><td>{row.issues.join(", ")}</td></tr>)}</tbody></table></div>
          {preview.encodingRepairProposed && <label className="confirm-check"><input type="checkbox" checked={acceptRepairs} onChange={event => setAcceptRepairs(event.target.checked)} /> Tôi đã xem trước và chấp nhận cách giải mã/đề xuất sửa encoding hiển thị.</label>}
          <button disabled={busy || !mapping.WORD || preview.rows.some(row => row.status === "AMBIGUOUS" && row.issues.includes("PART_OF_SPEECH_REVIEW_REQUIRED") && !posOverrides[row.rowNumber] && !row.values.POS)} onClick={() => void confirmImport()}>Xác nhận và lưu dòng hợp lệ</button>
        </div>}
      </section>}
    </div>
  );
}

function PronunciationList({ word }: { word: Vocabulary }) {
  const [items, setItems] = useState<{ id: string; accent: string; ipa: string | null; audioAvailable: boolean; audioPath: string | null }[]>([]);
  useEffect(() => {
    void api<Vocabulary & { pronunciations?: typeof items }>(`v1/vocabularies/${word.id}`).then(detail => setItems(detail.pronunciations ?? [])).catch(() => setItems([]));
  }, [word.id]);
  return items.length ? <ul className="pronunciation-list">{items.map(item => <li key={item.id}><strong>{item.accent}</strong> {item.ipa}{item.audioAvailable && item.audioPath && <audio controls src={item.audioPath} />}</li>)}</ul> : null;
}
