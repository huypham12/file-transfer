# QUY CHUẨN COMMIT CODE DỰ ÁN SECURESHARE (FILE-TRANSFER)

Dự án áp dụng quy chuẩn **Conventional Commits** để đảm bảo lịch sử git rõ ràng, hỗ trợ theo dõi thay đổi và tích hợp kiểm tra tự động trước mỗi commit.

---

## 1. Cú pháp chuẩn

Mọi commit message phải tuân thủ cú pháp:

```text
<type>(<scope>): <subject>
```

*Hoặc dạng rút gọn không kèm scope (cho các thay đổi tổng quát):*

```text
<type>: <subject>
```

### Ví dụ chuẩn:
- `feat(crypto): tích hợp thuật toán HKDF sinh mã xác thực SAS 6 chữ số`
- `fix(network): xử lý socket timeout khi ngắt kết nối đột ngột`
- `chore(config): cập nhật git hook commit-msg thuần shell`
- `docs(sad): hoàn thiện sơ đồ luồng dữ liệu DFD level 1`

---

## 2. Danh sách TYPE (Hành động)

| Type | Ý nghĩa | Mô tả / Khi nào sử dụng |
| :--- | :--- | :--- |
| **`feat`** | Feature | Thêm tính năng mới, giao diện mới, use case mới |
| **`fix`** | Bug fix | Sửa lỗi logic, sửa crash ứng dụng, sửa rò rỉ bộ nhớ |
| **`refactor`** | Refactoring | Tái cấu trúc code mà không làm thay đổi tính năng bên ngoài |
| **`perf`** | Performance | Tối ưu hóa hiệu năng (giảm RAM, tối ưu buffer pool, tăng tốc độ streaming) |
| **`test`** | Testing | Viết thêm hoặc sửa đổi Unit test, Instrumented test |
| **`docs`** | Documentation | Cập nhật tài liệu SRS, SAD, SDD, diagram, README |
| **`build`** | Build system | Thay đổi cấu hình build Gradle (`build.gradle.kts`), nâng version SDK/thư viện |
| **`ci`** | CI/CD | Cấu hình GitHub Actions, workflow kiểm tra tự động |
| **`chore`** | Chores | Các công việc phụ trợ (quản trị git, cấu hình hook, dọn dẹp asset) |
| **`revert`** | Revert | Hoàn tác lại commit trước đó |

---

## 3. Danh sách SCOPE (Phạm vi kiến trúc dự án)

Các scope được thiết kế bám sát theo các tầng kiến trúc trong tài liệu kiến trúc phần mềm (`sad.md`):

### Tầng Trình diễn (Presentation Layer)
- **`ui`**: Giao diện người dùng Jetpack Compose, màn hình, components, themes, animation.
- **`viewmodel`**: Các ViewModel quản lý trạng thái (`StateFlow`, `UIState`).

### Tầng Nghiệp vụ cốt lõi (Domain Layer)
- **`domain`**: Các UseCase nghiệp vụ (`StreamFileUseCase`, `RevokeSessionUseCase`), mô hình Domain.
- **`policy`**: Động cơ thẩm định chính sách chia sẻ (kiểm tra hạn dùng TTL, Passcode, số lượt tải).
- **`pairing`**: Luồng quét mã QR và xác thực bắt tay giữa hai thiết bị.

### Tầng Động cơ xử lý (Core Engines: Control Plane & Data Plane)
- **`protocol`**: Tầng điều khiển (Control Plane), bộ điều phối JSON-RPC, các bản tin trao đổi phiên.
- **`streaming`**: Tầng dữ liệu (Data Plane), cơ chế Chunk Streaming (64KB/256KB), Checkpoint Offset Resume.
- **`crypto`**: Mật mã học, bắt tay TLS 1.3, tính toán mã SAS qua HKDF, Keystore/TEE, SHA-256.
- **`buffer`**: Trình quản lý tái sử dụng bộ đệm (Zero-allocation Buffer Pool Manager).

### Tầng Hạ tầng & Nền tảng Android (Infrastructure Layer)
- **`network`**: Quản lý kết nối TCP Sockets (Server/Client), cấp phát cổng ngẫu nhiên, Wi-Fi.
- **`storage`**: Android Storage Access Framework (SAF), `ContentResolver`, `FileChannel` I/O.
- **`database`**: SQLite Room Database, Entities, DAOs, Migrations, Audit Log.
- **`service`**: Android Foreground Service (`DataTransferService`), Notification tiến trình.

### Quản trị & Hệ thống
- **`app`**: Application class, Dependency Injection container (Hilt/Koin).
- **`build`**: Cấu hình build Gradle, Android Gradle Plugin (AGP), ProGuard rules.
- **`docs`**: Tài liệu kỹ thuật dự án (SRS, SAD, SDD, checklists).
- **`test`**: Bộ kiểm thử tự động (JVM Test, Robolectric, Test Fixtures).
- **`ci`**: Kịch bản tự động hóa CI/CD GitHub Actions.
- **`config`**: Cấu hình công cụ, git hooks, editorconfig.

---

## 4. Quy tắc viết SUBJECT (Tiêu đề commit)

1. **Bắt đầu bằng chữ thường**: Viết thường chữ cái đầu (hỗ trợ cả tiếng Việt có dấu hoặc tiếng Anh).
2. **Không chấm câu**: Không để dấu chấm `.` ở cuối dòng subject.
3. **Ngắn gọn, súc tích**: Khuyến nghị dưới 72 ký tự, thể hiện rõ bản chất thay đổi.
4. **Tránh ghi mơ hồ**: Tránh các câu như `update code`, `fix bug`, `done task`...
5. **Thực tế, tránh đao to búa lớn**: Dùng từ ngữ lập trình thực tế, diễn đạt thẳng vào tính năng cụ thể (ví dụ: *"truyền nhận file qua socket LAN"*, *"quản lý bộ đệm"*); tuyệt đối tránh dịch máy/học thuật hóa/ví von đao to búa lớn như *"động cơ"*, *"cỗ máy"*, *"hạ tầng tối thượng"*...

---

## 5. Bảng đối chiếu Đúng / Sai thường gặp

| Commit Message | Đánh giá | Lý do |
| :--- | :---: | :--- |
| `feat(crypto): tích hợp mã hóa TLS 1.3` | ✅ **ĐÚNG** | Đầy đủ type, scope và subject chữ thường |
| `fix(network): xử lý socket timeout` | ✅ **ĐÚNG** | Đúng cấu trúc, ngắn gọn |
| `chore(config): cập nhật hook kiểm tra commit` | ✅ **ĐÚNG** | `config` là scope, type là `chore` |
| `update code test` | ❌ **SAI** | Thiếu `type:`, nội dung mơ hồ |
| `config: cập nhật hook kiểm tra` | ❌ **SAI** | `config` là scope, không phải type (sửa thành `chore(config): ...`) |
| `feat(ui): Thêm Màn Hình QR.` | ❌ **SAI** | Không viết hoa chữ cái đầu và không có dấu chấm ở cuối |

---

## 6. Kích hoạt Git Hook trên máy thành viên mới

Dự án đã tích hợp sẵn Git Hook thuần shell trong thư mục `.githooks/` (không yêu cầu cài đặt Node.js hay npm).

Mỗi thành viên khi clone repository về máy chỉ cần chạy lệnh sau một lần duy nhất trong Git Bash:

```bash
git config core.hooksPath .githooks
chmod +x .githooks/commit-msg
```

