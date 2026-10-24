# Bundles a photo as the launcher's full-screen wallpaper.
# Crops it to 16:9, scales it to 1920x1080 and saves it as app\res\drawable-nodpi\wallpaper_photo.jpg,
# then rebuilds. Does not touch the TV.
#
# Usage:  .\set_wallpaper.ps1 -Image "C:\path\to\photo.jpg"
param([Parameter(Mandatory = $true)][string]$Image)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Image)) { throw "Image not found: $Image" }
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile((Resolve-Path $Image))
$tw = 1920; $th = 1080
$scale = [Math]::Max($tw / $src.Width, $th / $src.Height)
$sw = [int]($src.Width * $scale); $sh = [int]($src.Height * $scale)
$bmp = New-Object System.Drawing.Bitmap $tw, $th
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.InterpolationMode = 'HighQualityBicubic'; $g.SmoothingMode = 'HighQuality'; $g.PixelOffsetMode = 'HighQuality'
$g.DrawImage($src, [int](($tw - $sw) / 2), [int](($th - $sh) / 2), $sw, $sh)
$g.Dispose(); $src.Dispose()
$dir = Join-Path $PSScriptRoot 'app\res\drawable-nodpi'
New-Item -ItemType Directory -Force $dir | Out-Null
$codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$ep = New-Object System.Drawing.Imaging.EncoderParameters 1
$ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]93)
$out = Join-Path $dir 'wallpaper_photo.jpg'
$bmp.Save($out, $codec, $ep); $bmp.Dispose()
Write-Host ("saved {0} ({1:N0} KB)" -f $out, ((Get-Item $out).Length / 1KB))
& (Join-Path $PSScriptRoot 'build.ps1')
