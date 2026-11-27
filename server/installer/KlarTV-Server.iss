; Klar TV Server – Setup für Windows (Inno Setup 6)
; Bauen: gradlew -p server createDistributable, dann ISCC.exe /DAppVersion=x.y.z server\installer\KlarTV-Server.iss
; Braucht Administratorrechte: richtet einen Windows-Dienst ein und öffnet den Port in der Firewall (nur Heimnetz).
; Daten liegen unter %ProgramData%\Klar TV Server, lesbar nur für Administratoren und das System.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{6F1C2A57-8E3B-4C1D-9A70-3B5E2D4F8C19}
AppName=Klar TV Server
AppVersion={#AppVersion}
AppVerName=Klar TV Server {#AppVersion}
AppPublisher=phillhf
AppPublisherURL=https://github.com/phillhf/klartv
AppSupportURL=https://github.com/phillhf/klartv/issues
AppUpdatesURL=https://github.com/phillhf/klartv/releases
VersionInfoVersion={#AppVersion}
VersionInfoDescription=Klar TV Server Setup
DefaultDirName=C:\Tools\Klar TV Server
AppendDefaultDirName=no
DirExistsWarning=no
UsePreviousAppDir=yes
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\build\setup
OutputBaseFilename=KlarTV-{#AppVersion}-Windows-Server-Setup
SetupIconFile=..\icon.ico
WizardStyle=modern
LicenseFile=..\..\LICENSE.md
UninstallDisplayName=Klar TV Server
UninstallDisplayIcon={app}\KlarTV-Server.exe
Compression=lzma2/max
SolidCompression=yes
ShowLanguageDialog=auto
CloseApplications=no

[Languages]
Name: "de"; MessagesFile: "compiler:Languages\German.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
de.PortTitle=Port im Heimnetz
en.PortTitle=Port in your home network
de.PortText=Unter diesem Port ist die Weboberfläche erreichbar, z. B. http://localhost:8095. Den Cloudflare-Tunnel richtest du auf genau diese Adresse ein.
en.PortText=The web interface is reachable on this port, e.g. http://localhost:8095. Point your Cloudflare tunnel to exactly this address.
de.PortLabel=Port (1024 bis 65535):
en.PortLabel=Port (1024 to 65535):
de.BadPort=Bitte einen Port zwischen 1024 und 65535 eingeben.
en.BadPort=Please enter a port between 1024 and 65535.
de.BlockedPort=Port %1 sperren Chrome, Edge und Firefox (er gehört zu einem anderen Dienst, z. B. 5060 für Internet-Telefonie). Bitte einen anderen nehmen, z. B. 8095.
en.BlockedPort=Chrome, Edge and Firefox block port %1 (it belongs to another service, e.g. 5060 for internet telephony). Please choose another one, e.g. 8095.
de.OpenNow=Weboberfläche jetzt öffnen (Benutzer und Passwort festlegen)
en.OpenNow=Open the web interface now (set user and password)
de.Uninstall=Klar TV Server deinstallieren
en.Uninstall=Uninstall Klar TV Server
de.OpenWeb=Klar TV Server öffnen
en.OpenWeb=Open Klar TV Server
de.BadDir=Bitte einen Unterordner auf einer lokalen Festplatte wählen, z. B. C:\Tools\Klar TV Server.
en.BadDir=Please choose a subfolder on a local drive, e.g. C:\Tools\Klar TV Server.
de.DeleteData=Sollen auch Anbieter, Favoriten, Verlauf, Zugangsdaten und Einstellungen des Servers gelöscht werden?
en.DeleteData=Should the server's providers, favorites, history, login and settings be deleted as well?
de.ServiceFailed=Der Dienst „Klar TV Server“ ließ sich nicht starten (Fehler %1). Näheres steht unter %ProgramData%\Klar TV Server\logs.
en.ServiceFailed=The "Klar TV Server" service could not be started (error %1). See %ProgramData%\Klar TV Server\logs.

[InstallDelete]
; Ein Update ersetzt Laufzeit und Werkzeuge – alte Dateien nicht liegen lassen
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "..\build\compose\binaries\main\app\KlarTV-Server\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\build\dienst\KlarTV-Server-Dienst.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "KlarTV-Server-Dienst.xml"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\..\LICENSE.md"; DestDir: "{app}"; DestName: "LICENSE.txt"; Flags: ignoreversion
Source: "DRITTANBIETER.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Klar TV Server\{cm:OpenWeb}"; Filename: "http://localhost:{code:GetPort}/"
Name: "{autoprograms}\Klar TV Server\{cm:Uninstall}"; Filename: "{uninstallexe}"

[Run]
Filename: "http://localhost:{code:GetPort}/"; Description: "{cm:OpenNow}"; Flags: postinstall shellexec nowait skipifsilent

[UninstallRun]
Filename: "{app}\KlarTV-Server-Dienst.exe"; Parameters: "stop"; Flags: runhidden waituntilterminated; RunOnceId: "StopService"
Filename: "{app}\KlarTV-Server-Dienst.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveService"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Klar TV Server"""; Flags: runhidden waituntilterminated; RunOnceId: "RemoveFirewall"

[UninstallDelete]
Type: dirifempty; Name: "{app}"

[Code]
var
  PortPage: TInputQueryWizardPage;

function DataDir(): String;
begin
  Result := ExpandConstant('{commonappdata}\Klar TV Server');
end;

{ Port aus der Einstellungsdatei des Servers, sonst 8095 }
function SavedPort(): String;
var
  Lines: TArrayOfString;
  I: Integer;
begin
  Result := '8095';
  if LoadStringsFromFile(DataDir() + '\server.properties', Lines) then
    for I := 0 to GetArrayLength(Lines) - 1 do
      if Pos('port=', Lines[I]) = 1 then
        Result := Trim(Copy(Lines[I], 6, 10));
end;

{ Ports, die Chrome, Edge und Firefox nicht öffnen (Liste der Browser ab 1024) }
function BlockedByBrowsers(P: Integer): Boolean;
begin
  case P of
    1719, 1720, 1723, 2049, 3659, 4045, 4190, 5060, 5061, 6000, 6566, 6665, 6666, 6667, 6668, 6669, 6679, 6697, 10080:
      Result := True;
  else
    Result := False;
  end;
end;

function GetPort(Param: String): String;
begin
  if PortPage <> nil then
    Result := Trim(PortPage.Values[0])
  else
    Result := SavedPort();
end;

procedure InitializeWizard();
begin
  PortPage := CreateInputQueryPage(wpSelectDir, CustomMessage('PortTitle'), '', CustomMessage('PortText'));
  PortPage.Add(CustomMessage('PortLabel'), False);
  PortPage.Values[0] := SavedPort();
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  D: String;
  P: Integer;
begin
  Result := True;
  if CurPageID = wpSelectDir then
  begin
    D := WizardForm.DirEdit.Text;
    if (Copy(D, 1, 2) = '\\') or (Length(D) <= 3) then
    begin
      MsgBox(CustomMessage('BadDir'), mbError, MB_OK);
      Result := False;
    end;
  end;
  if CurPageID = PortPage.ID then
  begin
    P := StrToIntDef(Trim(PortPage.Values[0]), 0);
    if (P < 1024) or (P > 65535) then
    begin
      MsgBox(CustomMessage('BadPort'), mbError, MB_OK);
      Result := False;
    end
    else if BlockedByBrowsers(P) then
    begin
      MsgBox(FmtMessage(CustomMessage('BlockedPort'), [IntToStr(P)]), mbError, MB_OK);
      Result := False;
    end;
  end;
end;

procedure Run(const Exe, Params: String; var Code: Integer);
begin
  if not Exec(Exe, Params, '', SW_HIDE, ewWaitUntilTerminated, Code) then
    Code := -1;
end;

{ Vor dem Kopieren: laufenden Dienst anhalten, damit die Dateien ersetzt werden können }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Run(ExpandConstant('{sys}\sc.exe'), 'stop KlarTVServer', Code);
  Sleep(3000);
  Run(ExpandConstant('{sys}\taskkill.exe'), '/F /IM KlarTV-Server.exe', Code);
  Sleep(500);
  Result := '';
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Code: Integer;
  Dienst, Data: String;
begin
  if CurStep <> ssPostInstall then Exit;
  Data := DataDir();
  ForceDirectories(Data + '\logs');
  { Zugangsdaten und Sitzungen liegen hier: nur Administratoren und das System }
  Run(ExpandConstant('{sys}\icacls.exe'), '"' + Data + '" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F *S-1-5-32-544:(OI)(CI)F /C /Q', Code);
  { Vorhandene Dateien erben davon. Nicht mit /T oben: Bei Dateien gelten (OI)(CI) nicht, sie hätten danach gar keine Rechte mehr }
  Run(ExpandConstant('{sys}\icacls.exe'), '"' + Data + '\*" /reset /T /C /Q', Code);
  SaveStringToFile(Data + '\server.properties', 'port=' + GetPort('') + #13#10, False);
  { Nur im Heimnetz und in der Domäne, nie im öffentlichen Netz }
  Run(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall delete rule name="Klar TV Server"', Code);
  Run(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall add rule name="Klar TV Server" dir=in action=allow profile=private,domain program="' +
    ExpandConstant('{app}') + '\KlarTV-Server.exe" enable=yes', Code);
  Dienst := ExpandConstant('{app}\KlarTV-Server-Dienst.exe');
  Run(Dienst, 'stop', Code);
  Run(Dienst, 'uninstall', Code);
  Run(Dienst, 'install', Code);
  Run(Dienst, 'start', Code);
  if Code <> 0 then
    SuppressibleMsgBox(FmtMessage(CustomMessage('ServiceFailed'), [IntToStr(Code)]), mbError, MB_OK, IDOK)
  else
    Sleep(4000);
end;

var
  DeleteData: Boolean;

function InitializeUninstall(): Boolean;
begin
  Result := True;
  DeleteData := False;
  if not UninstallSilent() then
    DeleteData := MsgBox(CustomMessage('DeleteData'), mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if (CurUninstallStep = usPostUninstall) and DeleteData then
    DelTree(DataDir(), True, True, True);
end;
