[Setup]
AppName=Kadero Agent
AppVersion=1.0.0
DefaultDirName={commonpf}\Kadero
DefaultGroupName=Kadero
OutputBaseFilename=KaderoAgentSetup
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible

[Files]
Source: "publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs
Source: "ffmpeg\ffmpeg.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Kadero Agent Setup"; Filename: "{app}\KaderoAgent.exe"; Parameters: "--setup"
Name: "{commonstartup}\Kadero Agent"; Filename: "{app}\KaderoAgent.exe"; Parameters: "--service"

[Run]
Filename: "sc.exe"; Parameters: "create KaderoAgent binPath= ""{app}\KaderoAgent.exe --service"" DisplayName= ""Kadero Screen Recorder"" start= auto"; Flags: runhidden
Filename: "sc.exe"; Parameters: "failure KaderoAgent reset= 86400 actions= restart/60000/restart/120000/restart/300000"; Flags: runhidden
Filename: "sc.exe"; Parameters: "start KaderoAgent"; Flags: runhidden
Filename: "{app}\KaderoAgent.exe"; Parameters: "--setup"; Description: "Configure connection"; Flags: postinstall nowait

[UninstallRun]
Filename: "sc.exe"; Parameters: "stop KaderoAgent"; Flags: runhidden
Filename: "sc.exe"; Parameters: "delete KaderoAgent"; Flags: runhidden

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DelTree(ExpandConstant('{commonappdata}\Kadero'), True, True, True);
  end;
end;
