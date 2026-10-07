# Vocabulary Learning Platform --- Project Specification

Bộ tài liệu này là source of truth cho Lab trước khi thương mại hóa.

## Quy tắc đọc tài liệu

1.  Đọc `AGENT_INIT_PROMPT.md`.
2.  Đọc `docs/ARCHITECTURE.md`, `docs/TECH_STACK.md`,
    `docs/PROJECT_RULES.md`, `docs/DATABASE_DESIGN.md`.
3.  Xác định phase/chức năng đang làm.
4.  Đọc SRS tương ứng trước khi sửa code.
5.  Không tự thay đổi architecture, công nghệ, schema contract hoặc rule
    đã chốt.
6.  Nếu SRS và code khác nhau, dừng và báo khác biệt trước khi tự ý sửa.

## Database contract

Thiết kế dữ liệu Lab được tóm tắt tại `docs/DATABASE_DESIGN.md`.
Các migration Flyway đầu tiên phải bám theo contract này trừ khi SRS
được cập nhật trước.

## Phase

-   Phase 01: Foundation (hoàn thành)
-   Phase 02: Vocabulary & Smart Import (đang hoàn thiện và xác minh cục bộ)
-   Phase 03: Learning Core
-   Phase 04: AI Personalization
-   Phase 05: Platform / Production Readiness

## Chạy môi trường Lab

Yêu cầu: Java 21, Maven, Node.js 20.9 trở lên và Python 3.10 trở lên.

1. Tạo `.env` từ `.env.example`, sau đó điền thông tin PostgreSQL của
   server. Không commit `.env`.
2. Chạy backend trong `backend/` bằng `mvn spring-boot:run`. Flyway tự
   áp dụng migration khi khởi động. Health check: `http://localhost:8081/api/health`.
3. Chạy frontend trong `frontend/`: sao chép `.env.example` thành
   `.env.local`, chạy `npm install`, rồi `npm run dev`. Mở
   `http://localhost:3000`; trang hiển thị kết quả gọi health endpoint
   của backend. Nếu đổi cổng/backend host, cập nhật `BACKEND_URL`.
4. Chạy AI service trong `ai-service/`: tạo môi trường ảo Python, cài
   `pip install -r requirements.txt`, rồi chạy
   `python -m src.main`. Health check: `http://localhost:8000/health`.

Phase 02 hiện dùng provider enrichment giả lập có kết quả xác định, không
gọi Cambridge, OpenAI hoặc Gemini. Có thể chạy luồng vocabulary/import mà
không cần API key. Muốn thử enrichment thì bật AI service ở bước 4.
Backend profile `local` tạo identity Lab từ `LAB_USER_ID` và
`LAB_USER_EMAIL`; xác thực người dùng thật và phân quyền quản trị sẽ được
thay bằng luồng platform ở Phase 05. Không bật `LAB_USER_ADMIN` trên môi
trường dùng chung.

Giới hạn còn lại trước khi chốt Phase 02: import nhận diện và kiểm tra cột
IPA nhưng chưa lưu phát âm từ file vì cần người dùng xác định IPA thuộc UK
hay US. API nhập IPA/audio trực tiếp đã có.

Kiểm tra Phase 02 đã chạy trong workspace:

-   `mvn -q "-Dtest=TextNormalizationServiceTest,ImportColumnDetectorTest,TabularFileParserTest" test`
-   `mvn -q "-Dtest=Phase2ApiIntegrationTest" test`
-   `npm run build` trong `frontend/`
-   Python unit tests cho AI mock service.

`docker-compose.yml` ghi chú cấu hình PostgreSQL đã dựng trên server.
Ứng dụng không chạy Docker Compose trong quy trình này.
