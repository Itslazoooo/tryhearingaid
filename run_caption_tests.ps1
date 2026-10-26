$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

$testClasses = @(
    "com.example.tryhearingaid.captions.CaptionUncertaintyPropertyTest",
    "com.example.tryhearingaid.captions.SupportedLanguagesPropertyTest",
    "com.example.tryhearingaid.captions.SpeakerLabelsPropertyTest",
    "com.example.tryhearingaid.captions.TranscriptExportRoundTripPropertyTest"
)

$testArgs = $testClasses | ForEach-Object { "--tests=$_" }

& .\gradlew.bat ":app:testDebugUnitTest" @testArgs 2>&1
