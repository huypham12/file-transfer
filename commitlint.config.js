'use strict';

/** @type {import('@commitlint/types').UserConfig} */
module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // Scopes chuẩn hóa theo kiến trúc SecureShare (P2P File Transfer Clean Architecture)
    // Cho phép để trống scope trong các commit tổng quan (level 2 + never mới cấm để trống).
    'scope-enum': [
      2,
      'always',
      [
        // 1. Tầng Trình diễn & Giao diện (Presentation Layer)
        'ui',           // Jetpack Compose screens, components, theme, animation
        'viewmodel',    // StateFlow, MVI/MVVM ViewModel logic

        // 2. Tầng Nghiệp vụ cốt lõi (Domain Layer)
        'domain',       // UseCases, Models, Session State Machine (12 states)
        'policy',       // Quy tắc kiểm soát: TTL, Passcode, số lượt tải tối đa
        'pairing',      // Luồng quét QR và ghép đôi thiết bị

        // 3. Tầng Động cơ xử lý (Core Engines: Control & Data Plane)
        'protocol',     // Control Plane: JSON-RPC dispatcher, bản tin bắt tay
        'streaming',    // Data Plane: Chunk streaming engine, Checkpoint offset, resume
        'crypto',       // Mật mã: TLS 1.3, tính toán mã SAS (HKDF), Keystore TEE, SHA-256
        'buffer',       // Buffer Pool Manager (zero-allocation pooling)

        // 4. Tầng Hạ tầng & Nền tảng (Infrastructure & Platform Layer)
        'network',      // TCP Server/Client Sockets, Port management, kết nối Wi-Fi
        'storage',      // SAF (Storage Access Framework), FileChannel, ContentResolver
        'database',     // Room SQLite, Entities, DAOs, Migrations
        'service',      // Android Foreground Service, notification lifecycle

        // 5. Quản trị dự án & Hệ thống (Cross-cutting / System)
        'app',          // Application class, Hilt/Koin DI container
        'build',        // Gradle dependencies, build.gradle.kts, SDK config, ProGuard
        'docs',         // SRS, SAD, SDD, Architecture diagrams, README
        'test',         // Unit test JVM, Android Instrumented test, Test fixtures
        'ci',           // GitHub Actions, CI/CD pipeline
        'config',       // Linting, git hooks, editorconfig
      ],
    ],
    // Hỗ trợ tiếng Việt hoặc tiếng Anh chữ thường; không bắt buộc chữ hoa đầu câu
    'subject-case': [2, 'never', ['sentence-case', 'start-case', 'pascal-case', 'upper-case']],
    // Giới hạn chiều dài dòng cho phần giải thích chi tiết trong body và footer
    'body-max-line-length': [2, 'always', 200],
    'footer-max-line-length': [2, 'always', 200],
  },
};

