<#
.SYNOPSIS
    Kịch bản sinh Keystore Ký số Bản phát hành (Release Keystore Generator) cho filetransfer.

.DESCRIPTION
    Sử dụng công cụ keytool của Java Development Kit (JDK) để khởi tạo cặp khóa
    ký số ứng dụng Android định dạng Java KeyStore (.jks) chuẩn Elliptic Curve (secp256r1)
    hoặc RSA-4096 phục vụ đóng gói và phân phối release APK/AAB.

.PARAMETER KeystorePath
    Đường dẫn tệp Keystore đầu ra (mặc định: filetransfer-release.jks)

.PARAMETER KeyAlias
    Tên định danh của khóa (mặc định: filetransfer_release_key)

.PARAMETER ValidityDays
    Thời hạn hiệu lực của chứng chỉ tính bằng ngày (mặc định: 10000 ngày ~ 27 năm)
#>

param(
    [string]$KeystorePath = "filetransfer-release.jks",
    [string]$KeyAlias = "filetransfer_release_key",
    [int]$ValidityDays = 10000,
    [string]$StorePassword = "filetransfer@SecureRelease2026",
    [string]$KeyPassword = "filetransfer@SecureRelease2026"
)

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "    filetransfer RELEASE KEYSTORE GENERATOR (STAGE 8)               " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# 1. Tìm kiếm công cụ keytool
$keytoolPath = "keytool"
try {
    $null = Get-Command keytool -ErrorAction Stop
} catch {
    # Thử tìm trong JAVA_HOME hoặc thư mục Android Studio JBR
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\keytool.exe")) {
        $keytoolPath = "$env:JAVA_HOME\bin\keytool.exe"
    } elseif (Test-Path "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe") {
        $keytoolPath = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
    } else {
        Write-Host "[LỖI] Không tìm thấy công cụ 'keytool' của Java trong hệ thống." -ForegroundColor Red
        Write-Host "Vui lòng cài đặt JDK hoặc cấu hình JAVA_HOME trên máy tính." -ForegroundColor Yellow
        exit 1
    }
}

Write-Host "[OK] Sử dụng keytool tại: $keytoolPath" -ForegroundColor Green

if (Test-Path $KeystorePath) {
    Write-Host "[CẢNH BÁO] Tệp $KeystorePath đã tồn tại. Đang sao lưu tệp cũ..." -ForegroundColor Yellow
    Move-Item -Path $KeystorePath -Destination "${KeystorePath}.bak" -Force
}

# 2. Sinh Keystore với thuật toán EC secp256r1
$dname = "CN=filetransfer Engineering, OU=Security Division, O=KMA, L=Hanoi, ST=Hanoi, C=VN"

Write-Host "Đang sinh cặp khóa ký số ECDSA secp256r1..." -ForegroundColor White

& $keytoolPath -genkeypair `
    -v `
    -keystore $KeystorePath `
    -alias $KeyAlias `
    -keyalg EC `
    -groupname secp256r1 `
    -sigalg SHA256withECDSA `
    -validity $ValidityDays `
    -storepass $StorePassword `
    -keypass $KeyPassword `
    -dname $dname

if ($LASTEXITCODE -eq 0 -and (Test-Path $KeystorePath)) {
    Write-Host "`n[THÀNH CÔNG] Đã sinh thành công Keystore tại: $KeystorePath" -ForegroundColor Green
    Write-Host "Tên Alias:        $KeyAlias" -ForegroundColor White
    Write-Host "Thuật toán:       ECDSA secp256r1 (SHA256withECDSA)" -ForegroundColor White
    Write-Host "Thời hạn:         $ValidityDays ngày" -ForegroundColor White
    
    Write-Host "`nĐể sử dụng khi biên dịch Release, hãy thêm vào gradle.properties hoặc biến môi trường:" -ForegroundColor Yellow
    Write-Host "-----------------------------------------------------------------" -ForegroundColor Gray
    Write-Host "KEYSTORE_FILE=$KeystorePath" -ForegroundColor Cyan
    Write-Host "KEYSTORE_PASSWORD=$StorePassword" -ForegroundColor Cyan
    Write-Host "KEY_ALIAS=$KeyAlias" -ForegroundColor Cyan
    Write-Host "KEY_PASSWORD=$KeyPassword" -ForegroundColor Cyan
    Write-Host "-----------------------------------------------------------------" -ForegroundColor Gray
} else {
    Write-Host "[LỖI] Sinh Keystore thất bại. Mã thoát: $LASTEXITCODE" -ForegroundColor Red
    exit 1
}

