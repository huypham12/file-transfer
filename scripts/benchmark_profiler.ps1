<#
.SYNOPSIS
    Kịch bản tự động hóa đo kiểm hiệu năng (RAM, CPU, Pin) ứng dụng TeamShare qua ADB
    phục vụ báo cáo thực nghiệm đồ án tốt nghiệp KMA.

.DESCRIPTION
    Kịch bản kết nối tới thiết bị Android thật qua Android Debug Bridge (ADB),
    định kỳ thu thập các chỉ số tài nguyên hệ thống (Memory PSS, CPU %, Battery Level)
    trong suốt quá trình truyền tệp tốc độ cao hoặc truyền ngầm tắt màn hình.

.PARAMETER PackageName
    Tên gói định danh của ứng dụng (mặc định: com.example.file_tranfer)

.PARAMETER DurationSeconds
    Thời gian đo kiểm tính bằng giây (mặc định: 60 giây)

.PARAMETER IntervalSeconds
    Chu kỳ lấy mẫu tài nguyên (mặc định: 1 giây)

.PARAMETER OutputCsv
    Đường dẫn tệp CSV lưu trữ số liệu (mặc định: benchmark_metrics.csv)

.EXAMPLE
    .\scripts\benchmark_profiler.ps1 -PackageName "com.example.file_tranfer" -DurationSeconds 120
#>

param(
    [string]$PackageName = "com.example.file_tranfer",
    [int]$DurationSeconds = 60,
    [int]$IntervalSeconds = 1,
    [string]$OutputCsv = "benchmark_metrics.csv"
)

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   TEAMSHARE ANDROID SYSTEM BENCHMARK PROFILER (STAGE 8)         " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "Gói ứng dụng mục tiêu: $PackageName" -ForegroundColor Yellow
Write-Host "Thời lượng đo kiểm:    $DurationSeconds giây (Chu kỳ: ${IntervalSeconds}s)" -ForegroundColor Yellow
Write-Host "Tệp kết quả:           $OutputCsv" -ForegroundColor Yellow

# 1. Kiểm tra kết nối ADB
$adbDevices = adb devices | Select-String -Pattern "\bdevice\b"
if (-not $adbDevices) {
    Write-Host "[LỖI] Không tìm thấy thiết bị Android nào được kết nối qua ADB." -ForegroundColor Red
    Write-Host "Vui lòng bật gỡ lỗi USB (USB Debugging) trên điện thoại và thử lại." -ForegroundColor Yellow
    exit 1
}

Write-Host "[OK] Đã phát hiện thiết bị: $($adbDevices[0].Line)" -ForegroundColor Green

# Tự động nhận diện nếu người dùng đang chạy bản Debug (.debug)
$debugPackage = "${PackageName}.debug"
$runningTarget = adb shell "pidof $PackageName" 2>$null
$runningDebug = adb shell "pidof $debugPackage" 2>$null

if (-not $runningTarget -and $runningDebug) {
    Write-Host "[THÔNG BÁO] Phát hiện ứng dụng bản Debug đang chạy. Tự động chuyển PackageName -> $debugPackage" -ForegroundColor Yellow
    $PackageName = $debugPackage
}

# 2. Khởi tạo tệp CSV
"Timestamp,ElapsedSec,TotalPssKb,TotalPssMb,NativeHeapKb,DalvikHeapKb,CpuPercent,BatteryLevel,BatteryTempC" | Out-File -FilePath $OutputCsv -Encoding UTF8

$startTime = Get-Date
$endTime = $startTime.AddSeconds($DurationSeconds)
$iteration = 0

$maxRamMb = 0.0
$sumRamMb = 0.0
$sampleCount = 0
$maxCpu = 0.0

Write-Host "`nBắt đầu thu thập số liệu... (Bấm Ctrl+C để dừng sớm)" -ForegroundColor Green

while ((Get-Date) -lt $endTime) {
    $now = Get-Date
    $elapsedSec = [math]::Round(($now - $startTime).TotalSeconds, 1)

    # A. Đo kiểm Bộ nhớ qua dumpsys meminfo
    $memInfo = adb shell dumpsys meminfo $PackageName 2>$null
    $totalPssKb = 0
    $nativeHeapKb = 0
    $dalvikHeapKb = 0

    if ($memInfo) {
        foreach ($line in $memInfo) {
            if ($line -match "TOTAL PSS:\s+(\d+)") {
                $totalPssKb = [int]$matches[1]
            } elseif ($line -match "Native Heap\s+(\d+)") {
                $nativeHeapKb = [int]$matches[1]
            } elseif ($line -match "Dalvik Heap\s+(\d+)") {
                $dalvikHeapKb = [int]$matches[1]
            }
        }
    }

    $totalPssMb = [math]::Round($totalPssKb / 1024, 2)

    # B. Đo kiểm CPU qua dumpsys cpuinfo
    $cpuInfo = adb shell dumpsys cpuinfo 2>$null | Select-String -Pattern "$PackageName"
    $cpuPercent = 0.0
    if ($cpuInfo -and ($cpuInfo[0].Line -match "(\d+(\.\d+)?)%\s+$PackageName")) {
        $cpuPercent = [double]$matches[1]
    }

    # C. Đo kiểm Pin qua dumpsys battery
    $batteryInfo = adb shell dumpsys battery 2>$null
    $batteryLevel = 100
    $batteryTempC = 25.0
    if ($batteryInfo) {
        foreach ($line in $batteryInfo) {
            if ($line -match "level:\s+(\d+)") {
                $batteryLevel = [int]$matches[1]
            } elseif ($line -match "temperature:\s+(\d+)") {
                $batteryTempC = [math]::Round(([int]$matches[1]) / 10.0, 1)
            }
        }
    }

    # Cập nhật thống kê
    if ($totalPssMb -gt $maxRamMb) { $maxRamMb = $totalPssMb }
    if ($cpuPercent -gt $maxCpu) { $maxCpu = $cpuPercent }
    $sumRamMb += $totalPssMb
    $sampleCount++

    # Ghi nhận vào CSV
    "$($now.ToString("yyyy-MM-dd HH:mm:ss")),$elapsedSec,$totalPssKb,$totalPssMb,$nativeHeapKb,$dalvikHeapKb,$cpuPercent,$batteryLevel,$batteryTempC" | Out-File -FilePath $OutputCsv -Append -Encoding UTF8

    Write-Host "[$elapsedSec s] RAM: ${totalPssMb} MB (Đỉnh: ${maxRamMb} MB) | CPU: ${cpuPercent}% | Pin: ${batteryLevel}% (${batteryTempC}°C)" -ForegroundColor Gray

    Start-Sleep -Seconds $IntervalSeconds
}

$avgRamMb = if ($sampleCount -gt 0) { [math]::Round($sumRamMb / $sampleCount, 2) } else { 0.0 }

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "                KẾT QUẢ TỔNG HỢP ĐO KIỂM HIỆU NĂNG               " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "Tổng số mẫu đo:           $sampleCount mẫu" -ForegroundColor White
Write-Host "Mức RAM đỉnh (Peak RAM):  $maxRamMb MB (Mục tiêu: <= 120 MB) -> $(if ($maxRamMb -le 120.0) { '[ĐẠT]' } else { '[VƯỢT NGƯỠNG]' })" -ForegroundColor $(if ($maxRamMb -le 120.0) { 'Green' } else { 'Red' })
Write-Host "Mức RAM trung bình:       $avgRamMb MB" -ForegroundColor White
Write-Host "Mức CPU đỉnh (Peak CPU):  $maxCpu % (Mục tiêu: <= 25 %)" -ForegroundColor White
Write-Host "Số liệu chi tiết lưu tại: $OutputCsv" -ForegroundColor Yellow
Write-Host "=================================================================" -ForegroundColor Cyan

