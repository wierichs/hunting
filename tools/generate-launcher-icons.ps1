param([string]$PreviewPath)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName PresentationCore
Add-Type -AssemblyName WindowsBase

$res = Join-Path $PSScriptRoot '..\app\src\main\res'
$stream = [System.IO.File]::OpenRead((Join-Path $PSScriptRoot '..\Logo.png'))
try {
    $frame = [System.Windows.Media.Imaging.PngBitmapDecoder]::new($stream, 'PreservePixelFormat', 'OnLoad').Frames[0]
} finally { $stream.Dispose() }
if ($frame.PixelWidth -ne $frame.PixelHeight) { throw 'Logo.png must be square.' }
$source = [System.Windows.Media.Imaging.FormatConvertedBitmap]::new(
    $frame, [System.Windows.Media.PixelFormats]::Bgra32, $null, 0)
$width = $source.PixelWidth
$stride = $width * 4
$pixels = [byte[]]::new($stride * $width)
$source.CopyPixels($pixels, $stride, 0)
$monoPixels = [byte[]]::new($pixels.Length)
$center = ($width - 1) / 2
$sourceRadiusSquared = [math]::Pow($width * 0.515, 2)
$maxRadiusSquared = 0
$foregroundPixels = 0

for ($y = 0; $y -lt $width; $y++) {
    for ($x = 0; $x -lt $width; $x++) {
        $i = ($y * $width + $x) * 4
        $distanceSquared = ($x - $center) * ($x - $center) + ($y - $center) * ($y - $center)
        $alpha = 0
        # The white outer corners are canvas, not part of the map.
        if ($distanceSquared -le $sourceRadiusSquared) {
            $r = [double]$pixels[$i + 2]
            $g = [double]$pixels[$i + 1]
            $b = [double]$pixels[$i]
            $white = [math]::Min(($r - 55) / 200, [math]::Min(($g - 93) / 162, ($b - 82) / 173))
            $gold = [math]::Min(($r - $g + 38) / 84, ($g - $b - 11) / 101)
            $coverage = [math]::Min(1, [math]::Max($white, $gold))
            if ($coverage -gt 0.15) {
                $alpha = [byte][math]::Round($coverage * $pixels[$i + 3])
                if ($gold -gt $white) {
                    $pixels[$i] = 64; $pixels[$i + 1] = 176; $pixels[$i + 2] = 222
                } else {
                    $pixels[$i] = 255; $pixels[$i + 1] = 255; $pixels[$i + 2] = 255
                }
            }
        }
        $pixels[$i + 3] = $alpha
        $monoPixels[$i] = 82; $monoPixels[$i + 1] = 93; $monoPixels[$i + 2] = 55
        $monoPixels[$i + 3] = $alpha
        if ($alpha -gt 0) {
            $foregroundPixels++
            $maxRadiusSquared = [math]::Max($maxRadiusSquared, $distanceSquared)
        }
    }
}
if ($foregroundPixels -eq 0) { throw 'No white or gold motif could be extracted from Logo.png.' }
$extent = $width * 32.5 / [math]::Sqrt($maxRadiusSquared)
$sourceArt = [System.Windows.Media.Imaging.BitmapSource]::Create(
    $width, $width, 96, 96, [System.Windows.Media.PixelFormats]::Bgra32, $null, $pixels, $stride)
$sourceMono = [System.Windows.Media.Imaging.BitmapSource]::Create(
    $width, $width, 96, 96, [System.Windows.Media.PixelFormats]::Bgra32, $null, $monoPixels, $stride)

function Render-Artwork($Image) {
    $visual = [System.Windows.Media.DrawingVisual]::new()
    [System.Windows.Media.RenderOptions]::SetBitmapScalingMode($visual, 'HighQuality')
    $drawing = $visual.RenderOpen()
    $drawing.PushTransform([System.Windows.Media.ScaleTransform]::new(8, 8))
    $drawing.DrawImage($Image, [System.Windows.Rect]::new(
        (108 - $extent) / 2, (108 - $extent) / 2, $extent, $extent))
    $drawing.Close()
    $bitmap = [System.Windows.Media.Imaging.RenderTargetBitmap]::new(
        864, 864, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
    $bitmap.Render($visual)
    return $bitmap
}

$artwork = Render-Artwork $sourceArt
$monochrome = Render-Artwork $sourceMono
$artPixels = [byte[]]::new(864 * 864 * 4)
$artwork.CopyPixels($artPixels, 864 * 4, 0)
for ($y = 0; $y -lt 864; $y++) {
    for ($x = 0; $x -lt 864; $x++) {
        if ($artPixels[($y * 864 + $x) * 4 + 3] -gt 0 -and
            [math]::Pow(($x + 0.5) / 8 - 54, 2) + [math]::Pow(($y + 0.5) / 8 - 54, 2) -gt 1089) {
            throw 'The rendered foreground exceeds the adaptive icon safe circle (66 dp).'
        }
    }
}

function Save-Png($Bitmap, [string]$Path) {
    $encoder = [System.Windows.Media.Imaging.PngBitmapEncoder]::new()
    $encoder.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($Bitmap))
    $stream = [System.IO.File]::Create($Path)
    try { $encoder.Save($stream) } finally { $stream.Dispose() }
}

$artworkFolder = Join-Path $res 'drawable-nodpi'
New-Item -ItemType Directory -Path $artworkFolder -Force | Out-Null
Save-Png $artwork (Join-Path $artworkFolder 'ic_launcher_artwork.png')

function Render-Icon([int]$Size, [bool]$Round, [bool]$Mono = $false) {
    $visual = [System.Windows.Media.DrawingVisual]::new()
    [System.Windows.Media.RenderOptions]::SetBitmapScalingMode($visual, 'HighQuality')
    $drawing = $visual.RenderOpen()
    if ($Round) {
        $drawing.PushClip([System.Windows.Media.EllipseGeometry]::new(
            [System.Windows.Point]::new($Size / 2, $Size / 2), $Size / 2, $Size / 2))
    } else {
        $drawing.PushClip([System.Windows.Media.RectangleGeometry]::new(
            [System.Windows.Rect]::new(0, 0, $Size, $Size), $Size * 0.2, $Size * 0.2))
    }
    $background = if ($Mono) { '#DCE8DF' } else { '#375D52' }
    $drawing.DrawRectangle([System.Windows.Media.BrushConverter]::new().ConvertFromInvariantString($background),
        $null, [System.Windows.Rect]::new(0, 0, $Size, $Size))
    $drawing.PushTransform([System.Windows.Media.ScaleTransform]::new($Size / 72, $Size / 72))
    $drawing.PushTransform([System.Windows.Media.TranslateTransform]::new(-18, -18))
    $image = if ($Mono) { $monochrome } else { $artwork }
    $drawing.DrawImage($image, [System.Windows.Rect]::new(0, 0, 108, 108))
    $drawing.Close()
    $bitmap = [System.Windows.Media.Imaging.RenderTargetBitmap]::new(
        $Size, $Size, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
    $bitmap.Render($visual)
    return $bitmap
}

foreach ($density in @{ mdpi = 48; hdpi = 72; xhdpi = 96; xxhdpi = 144; xxxhdpi = 192 }.GetEnumerator()) {
    $folder = Join-Path $res "mipmap-$($density.Key)"
    foreach ($round in @($false, $true)) {
        $name = if ($round) { 'ic_launcher_round' } else { 'ic_launcher' }
        Save-Png (Render-Icon $density.Value $round) (Join-Path $folder "$name.png")
    }
}

if ($PreviewPath) {
    $visual = [System.Windows.Media.DrawingVisual]::new()
    $drawing = $visual.RenderOpen()
    $drawing.DrawRectangle([System.Windows.Media.Brushes]::White, $null,
        [System.Windows.Rect]::new(0, 0, 720, 300))
    $drawing.DrawImage((Render-Icon 256 $false), [System.Windows.Rect]::new(16, 16, 256, 256))
    $drawing.DrawImage((Render-Icon 256 $true), [System.Windows.Rect]::new(288, 16, 256, 256))
    $drawing.DrawImage((Render-Icon 96 $true $true), [System.Windows.Rect]::new(584, 24, 96, 96))
    $drawing.DrawImage((Render-Icon 48 $true), [System.Windows.Rect]::new(576, 160, 48, 48))
    $drawing.DrawImage((Render-Icon 32 $true), [System.Windows.Rect]::new(648, 168, 32, 32))
    $drawing.Close()
    $bitmap = [System.Windows.Media.Imaging.RenderTargetBitmap]::new(
        720, 300, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
    $bitmap.Render($visual)
    Save-Png $bitmap $PreviewPath
}
