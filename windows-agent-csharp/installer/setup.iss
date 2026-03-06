; =============================================================================
; Kadero Agent Installer
; Inno Setup 6.x script
;
; Supports interactive wizard and silent install:
;   KaderoAgentSetup.exe /VERYSILENT /SERVERURL=https://example.com /REGTOKEN=drt_xxx
; =============================================================================

[Setup]
AppName=Kadero Agent
AppVersion=1.0.0
AppPublisher=Kadero
AppPublisherURL=https://kadero.ru
DefaultDirName={commonpf}\Kadero
DefaultGroupName=Kadero
OutputBaseFilename=KaderoAgentSetup
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
LicenseFile=LICENSE.txt
UninstallDisplayIcon={app}\KaderoAgent.exe
WizardStyle=modern
DisableProgramGroupPage=yes
MinVersion=10.0

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

; ---------------------------------------------------------------------------
; Files to install
; ---------------------------------------------------------------------------
[Files]
Source: "publish\KaderoAgent.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "publish\*.dll"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist
Source: "publish\*.json"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist
Source: "ffmpeg\ffmpeg.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "appsettings.json"; DestDir: "{app}"; Flags: ignoreversion
Source: "publish\log4net.config"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist
Source: "LICENSE.txt"; DestDir: "{app}"; Flags: ignoreversion

; ---------------------------------------------------------------------------
; Directories with explicit permissions
; ---------------------------------------------------------------------------
[Dirs]
Name: "{commonappdata}\Kadero"; Permissions: admins-full system-full
Name: "{commonappdata}\Kadero\logs"; Permissions: admins-full system-full users-modify
Name: "{commonappdata}\Kadero\segments"; Permissions: admins-full system-full
Name: "{commonappdata}\Kadero\db"; Permissions: admins-full system-full

; ---------------------------------------------------------------------------
; Registry: tray auto-start + uninstall cleanup
; ---------------------------------------------------------------------------
[Registry]
Root: HKLM; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Run"; \
  ValueType: string; ValueName: "KaderoTray"; \
  ValueData: """{app}\KaderoAgent.exe"" --tray"; \
  Flags: uninsdeletevalue

; ---------------------------------------------------------------------------
; Start Menu shortcuts
; ---------------------------------------------------------------------------
[Icons]
Name: "{group}\Kadero Agent"; Filename: "{app}\KaderoAgent.exe"; Parameters: "--tray"
Name: "{group}\{cm:UninstallProgram,Kadero Agent}"; Filename: "{uninstallexe}"

; ---------------------------------------------------------------------------
; Post-install actions (sequential, order matters)
; ---------------------------------------------------------------------------
[Run]
; Protect root Kadero directory: only SYSTEM and Administrators
Filename: "icacls.exe"; \
  Parameters: """{commonappdata}\Kadero"" /inheritance:r /grant:r ""*S-1-5-18:(OI)(CI)F"" /grant:r ""*S-1-5-32-544:(OI)(CI)F"""; \
  StatusMsg: "Настройка прав доступа..."; \
  Flags: runhidden

; Protect segments directory separately (double protection)
Filename: "icacls.exe"; \
  Parameters: """{commonappdata}\Kadero\segments"" /inheritance:r /grant:r ""*S-1-5-18:(OI)(CI)F"" /grant:r ""*S-1-5-32-544:(OI)(CI)F"""; \
  Flags: runhidden

; Register device with server (headless, saves config)
Filename: "{app}\KaderoAgent.exe"; \
  Parameters: "--register --server-url={code:GetServerUrl} --token={code:GetRegToken}"; \
  StatusMsg: "Регистрация устройства на сервере..."; \
  Flags: runhidden waituntilterminated

; Create Windows Service
Filename: "sc.exe"; \
  Parameters: "create KaderoAgent binPath= ""{app}\KaderoAgent.exe --service"" DisplayName= ""Kadero Screen Recorder"" start= auto"; \
  StatusMsg: "Создание службы Windows..."; \
  Flags: runhidden

; Configure automatic restart on failure (3 attempts with increasing delays)
Filename: "sc.exe"; \
  Parameters: "failure KaderoAgent reset= 86400 actions= restart/60000/restart/120000/restart/300000"; \
  Flags: runhidden

; Set service description
Filename: "sc.exe"; \
  Parameters: "description KaderoAgent ""Kadero screen recording agent"""; \
  Flags: runhidden

; Start the service
Filename: "sc.exe"; \
  Parameters: "start KaderoAgent"; \
  StatusMsg: "Запуск службы..."; \
  Flags: runhidden

; Launch tray application after install (checked by default, runs as the real user)
Filename: "{app}\KaderoAgent.exe"; \
  Parameters: "--tray"; \
  Description: "Запустить Кадеро в области уведомлений"; \
  Flags: postinstall nowait runasoriginaluser

; ---------------------------------------------------------------------------
; Uninstall actions (order: kill processes, stop service, delete service)
; ---------------------------------------------------------------------------
[UninstallRun]
; Kill all running instances of KaderoAgent (tray + any other)
Filename: "taskkill.exe"; Parameters: "/f /im KaderoAgent.exe"; \
  RunOnceId: "KillAgent"; Flags: runhidden
; Stop the Windows Service
Filename: "sc.exe"; Parameters: "stop KaderoAgent"; \
  RunOnceId: "StopService"; Flags: runhidden
; Wait for service to fully stop
Filename: "cmd.exe"; Parameters: "/c timeout /t 3 /nobreak >nul"; \
  RunOnceId: "WaitStop"; Flags: runhidden
; Delete the service
Filename: "sc.exe"; Parameters: "delete KaderoAgent"; \
  RunOnceId: "DeleteService"; Flags: runhidden

[InstallDelete]
Type: files; Name: "{app}\unins*.exe"
Type: files; Name: "{app}\unins*.dat"

; ---------------------------------------------------------------------------
; Pascal Script: custom wizard page, validation, silent install support
; ---------------------------------------------------------------------------
[Code]

var
  ConnectionPage: TWizardPage;
  ServerUrlLabel: TNewStaticText;
  ServerUrlEdit: TNewEdit;
  ServerUrlHint: TNewStaticText;
  RegTokenLabel: TNewStaticText;
  RegTokenEdit: TNewEdit;
  RegTokenHint: TNewStaticText;

// ---- Helper: read command-line parameter /PARAM=value ----

function GetCommandLineParam(const ParamName: String): String;
begin
  Result := ExpandConstant('{param:' + ParamName + '|}');
end;

// ---- Scripted constants for [Run] section ----

function GetServerUrl(Param: String): String;
begin
  Result := GetCommandLineParam('SERVERURL');
  if Result = '' then
    Result := Trim(ServerUrlEdit.Text);
end;

function GetRegToken(Param: String): String;
begin
  Result := GetCommandLineParam('REGTOKEN');
  if Result = '' then
    Result := Trim(RegTokenEdit.Text);
end;

// ---- Create the custom "Connection Settings" wizard page ----

procedure InitializeWizard;
begin
  // Insert custom page after directory selection
  ConnectionPage := CreateCustomPage(
    wpSelectDir,
    'Настройка подключения',
    'Укажите параметры подключения к серверу Кадеро'
  );

  // --- Server URL ---
  ServerUrlLabel := TNewStaticText.Create(ConnectionPage);
  ServerUrlLabel.Parent := ConnectionPage.Surface;
  ServerUrlLabel.Caption := 'Адрес сервера:';
  ServerUrlLabel.Top := 0;
  ServerUrlLabel.Left := 0;
  ServerUrlLabel.Font.Style := [fsBold];

  ServerUrlEdit := TNewEdit.Create(ConnectionPage);
  ServerUrlEdit.Parent := ConnectionPage.Surface;
  ServerUrlEdit.Top := 22;
  ServerUrlEdit.Left := 0;
  ServerUrlEdit.Width := ConnectionPage.SurfaceWidth;
  ServerUrlEdit.Text := 'https://';

  ServerUrlHint := TNewStaticText.Create(ConnectionPage);
  ServerUrlHint.Parent := ConnectionPage.Surface;
  ServerUrlHint.Caption := 'Например: https://services.shepaland.ru/screenrecorder';
  ServerUrlHint.Top := 48;
  ServerUrlHint.Left := 0;
  ServerUrlHint.Font.Color := clGray;

  // --- Registration Token ---
  RegTokenLabel := TNewStaticText.Create(ConnectionPage);
  RegTokenLabel.Parent := ConnectionPage.Surface;
  RegTokenLabel.Caption := 'Токен регистрации:';
  RegTokenLabel.Top := 80;
  RegTokenLabel.Left := 0;
  RegTokenLabel.Font.Style := [fsBold];

  RegTokenEdit := TNewEdit.Create(ConnectionPage);
  RegTokenEdit.Parent := ConnectionPage.Surface;
  RegTokenEdit.Top := 102;
  RegTokenEdit.Left := 0;
  RegTokenEdit.Width := ConnectionPage.SurfaceWidth;
  RegTokenEdit.Text := '';

  RegTokenHint := TNewStaticText.Create(ConnectionPage);
  RegTokenHint.Parent := ConnectionPage.Surface;
  RegTokenHint.Caption := 'Токен начинается с drt_ и выдаётся администратором системы.';
  RegTokenHint.Top := 128;
  RegTokenHint.Left := 0;
  RegTokenHint.Font.Color := clGray;
end;

// ---- Validation when user clicks Next ----

function NextButtonClick(CurPageID: Integer): Boolean;
var
  Url, Token, UrlLower: String;
begin
  Result := True;

  if CurPageID = ConnectionPage.ID then
  begin
    Url := Trim(ServerUrlEdit.Text);
    Token := Trim(RegTokenEdit.Text);
    UrlLower := Lowercase(Url);

    // Validate: server URL must be non-empty and start with http(s)://
    if (Url = '') or (Url = 'https://') or (Url = 'http://') then
    begin
      MsgBox('Укажите адрес сервера.', mbError, MB_OK);
      Result := False;
      Exit;
    end;

    if (Pos('http://', UrlLower) <> 1) and (Pos('https://', UrlLower) <> 1) then
    begin
      MsgBox('Адрес сервера должен начинаться с http:// или https://', mbError, MB_OK);
      Result := False;
      Exit;
    end;

    // Validate: registration token is required and must start with drt_
    if Token = '' then
    begin
      MsgBox('Укажите токен регистрации.', mbError, MB_OK);
      Result := False;
      Exit;
    end;

    if Pos('drt_', Token) <> 1 then
    begin
      MsgBox('Токен регистрации должен начинаться с drt_', mbError, MB_OK);
      Result := False;
      Exit;
    end;
  end;
end;

// ---- Skip custom page when command-line params provided (silent / GPO) ----

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if PageID = ConnectionPage.ID then
  begin
    if (GetCommandLineParam('SERVERURL') <> '') and
       (GetCommandLineParam('REGTOKEN') <> '') then
      Result := True;
  end;
end;

// ---- Validate silent install parameters ----

function InitializeSetup: Boolean;
var
  ServerUrl, RegToken, UrlLower: String;
begin
  Result := True;

  // In silent mode, validate that required params are provided
  if WizardSilent then
  begin
    ServerUrl := GetCommandLineParam('SERVERURL');
    RegToken := GetCommandLineParam('REGTOKEN');

    if (ServerUrl = '') or (RegToken = '') then
    begin
      MsgBox('Для тихой установки необходимо указать параметры /SERVERURL и /REGTOKEN.' + #13#10 +
             'Пример: KaderoAgentSetup.exe /VERYSILENT /SERVERURL=https://server.example.com /REGTOKEN=drt_abc123',
             mbCriticalError, MB_OK);
      Result := False;
      Exit;
    end;

    UrlLower := Lowercase(ServerUrl);
    if (Pos('http://', UrlLower) <> 1) and (Pos('https://', UrlLower) <> 1) then
    begin
      MsgBox('Параметр /SERVERURL должен начинаться с http:// или https://', mbCriticalError, MB_OK);
      Result := False;
      Exit;
    end;

    if Pos('drt_', RegToken) <> 1 then
    begin
      MsgBox('Параметр /REGTOKEN должен начинаться с drt_', mbCriticalError, MB_OK);
      Result := False;
      Exit;
    end;
  end;
end;

// ---- Stop existing service/processes before upgrade ----

procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  if CurStep = ssInstall then
  begin
    // Kill tray instances before overwriting the executable
    Exec('taskkill.exe', '/f /im KaderoAgent.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    // Stop existing service (ignore errors if not installed)
    Exec('sc.exe', 'stop KaderoAgent', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    // Brief pause to release file handles
    Sleep(1500);
  end;
end;

// ---- Uninstall: ask whether to remove recording data ----

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
  begin
    if MsgBox(
      'Удалить данные записей и конфигурацию?' + #13#10 + #13#10 +
      'Директория: ' + ExpandConstant('{commonappdata}\Kadero') + #13#10 + #13#10 +
      'Если вы выберете "Нет", данные сохранятся и могут быть ' +
      'использованы при повторной установке.',
      mbConfirmation,
      MB_YESNO or MB_DEFBUTTON2
    ) = IDYES then
    begin
      DelTree(ExpandConstant('{commonappdata}\Kadero'), True, True, True);
    end;
  end;
end;
