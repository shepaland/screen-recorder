[Setup]
AppName=PRG Screen Recorder
AppVersion=1.0.0
AppPublisher=PRG
DefaultDirName={autopf}\PRG Screen Recorder
DefaultGroupName=PRG Screen Recorder
OutputBaseFilename=PRGScreenRecorderSetup
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Files]
Source: "..\target\windows-agent-1.0.0.jar"; DestDir: "{app}"; DestName: "agent.jar"; Flags: ignoreversion
Source: "..\src\main\resources\agent.properties"; DestDir: "{app}\config"; Flags: ignoreversion
Source: "service-wrapper.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "uninstall-service.bat"; DestDir: "{app}"; Flags: ignoreversion

; Bundled JRE will be added later
; Source: "jre\*"; DestDir: "{app}\jre"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{group}\PRG Screen Recorder"; Filename: "{app}\jre\bin\javaw.exe"; Parameters: "-jar ""{app}\agent.jar"""; WorkingDir: "{app}"
Name: "{group}\Uninstall PRG Screen Recorder"; Filename: "{uninstallexe}"
Name: "{commonstartup}\PRG Screen Recorder"; Filename: "{app}\jre\bin\javaw.exe"; Parameters: "-jar ""{app}\agent.jar"""; WorkingDir: "{app}"

[Run]
Filename: "{app}\service-wrapper.bat"; Parameters: "install ""{app}"""; Flags: runhidden waituntilterminated; StatusMsg: "Installing service..."
Filename: "sc.exe"; Parameters: "start PRGScreenRecorder"; Flags: runhidden; StatusMsg: "Starting service..."

[UninstallRun]
Filename: "{app}\uninstall-service.bat"; Flags: runhidden waituntilterminated

[Code]
// Custom Inno Setup Pascal code for checking Java
function InitializeSetup(): Boolean;
begin
  Result := True;
end;
