import VocabularyDashboard from "@/components/vocabulary-dashboard";

export const dynamic = "force-dynamic";

type BackendHealth = { status: string; database: string };

async function getBackendHealth(): Promise<BackendHealth | null> {
  const backendUrl = process.env.BACKEND_URL ?? "http://localhost:8081";
  try {
    const response = await fetch(`${backendUrl}/api/health`, { cache: "no-store" });
    if (!response.ok) return null;
    return (await response.json()) as BackendHealth;
  } catch {
    return null;
  }
}

export default async function Home() {
  const health = await getBackendHealth();
  return (
    <main>
      <h1>WordPractice</h1>
      <p>Nền tảng học từ vựng tiếng Anh</p>
      <section aria-labelledby="backend-status">
        <h2 id="backend-status">Backend</h2>
        <p>{health ? `API: ${health.status} · Database: ${health.database}` : "Chưa kết nối được backend"}</p>
      </section>
      <VocabularyDashboard />
    </main>
  );
}
