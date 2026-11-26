; Klar TV – Setup für Windows (Inno Setup 6)
; Bauen: gradlew -p desktop createDistributable, dann ISCC.exe /DAppVersion=x.y.z desktop\installer\KlarTV.iss
; Ohne Administratorrechte. Daten liegen unter %LOCALAPPDATA%\Klar TV, Aufnahmen unter Videos\Klar TV.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppId={{B0E5B0B6-3E0F-4B86-9D6B-5C6C1D0E7A31}
AppName=Klar TV
AppVersion={#AppVersion}
AppVerName=Klar TV {#AppVersion}
AppPublisher=phillhf
AppPublisherURL=https://github.com/phillhf/klartv
AppSupportURL=https://github.com/phillhf/klartv/issues
AppUpdatesURL=https://github.com/phillhf/klartv/releases
VersionInfoVersion={#AppVersion}
VersionInfoDescription=Klar TV Setup
DefaultDirName=C:\Tools\Klar TV
AppendDefaultDirName=no
DirExistsWarning=no
UsePreviousAppDir=yes
UsePreviousTasks=yes
DisableProgramGroupPage=yes
DisableWelcomePage=no
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\build\setup
OutputBaseFilename=KlarTV-{#AppVersion}-Windows-Setup
SetupIconFile=..\icon.ico
WizardStyle=modern
LicenseFile=..\..\LICENSE.md
UninstallDisplayName=Klar TV
UninstallDisplayIcon={app}\KlarTV.exe
Compression=lzma2/max
SolidCompression=yes
CloseApplications=yes
RestartApplications=no
ShowLanguageDialog=auto

[Languages]
Name: "de"; MessagesFile: "compiler:Languages\German.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
de.DesktopIcon=Verknüpfung auf dem Desktop anlegen
en.DesktopIcon=Create a desktop shortcut
de.LaunchNow=Klar TV jetzt starten
en.LaunchNow=Start Klar TV now
de.Uninstall=Klar TV deinstallieren
en.Uninstall=Uninstall Klar TV
de.BadDir=Bitte einen Unterordner auf einer lokalen Festplatte wählen, z. B. C:\Tools\Klar TV.
en.BadDir=Please choose a subfolder on a local drive, e.g. C:\Tools\Klar TV.
de.DeleteData=Sollen auch Anbieter, Favoriten, Verlauf und Einstellungen gelöscht werden?%n%nAufnahmen und Downloads im Ordner „Videos“ bleiben in jedem Fall erhalten.
en.DeleteData=Should providers, favorites, history and settings be deleted as well?%n%nRecordings and downloads in the “Videos” folder are always kept.

[Tasks]
Name: "desktopicon"; Description: "{cm:DesktopIcon}"

[InstallDelete]
; Ein Update ersetzt Laufzeit und VLC – alte Dateien nicht liegen lassen
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "..\build\compose\binaries\main\app\KlarTV\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\LICENSE.md"; DestDir: "{app}"; DestName: "LICENSE.txt"; Flags: ignoreversion
Source: "DRITTANBIETER.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Klar TV\Klar TV"; Filename: "{app}\KlarTV.exe"; WorkingDir: "{app}"
Name: "{autoprograms}\Klar TV\{cm:Uninstall}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Klar TV"; Filename: "{app}\KlarTV.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\KlarTV.exe"; WorkingDir: "{app}"; Description: "{cm:LaunchNow}"; Flags: postinstall nowait skipifsilent
; Update aus der App (still mit /RELAUNCH=1): danach gleich wieder starten
Filename: "{app}\KlarTV.exe"; WorkingDir: "{app}"; Flags: nowait skipifnotsilent; Check: Relaunch

[UninstallDelete]
Type: dirifempty; Name: "{app}"

[Code]
function Relaunch(): Boolean;
begin
  Result := ExpandConstant('{param:RELAUNCH|0}') = '1';
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  D: String;
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
end;

// Stilles Update: laufendes Klar TV beenden, damit die Dateien ersetzt werden koennen
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  if WizardSilent() then
  begin
    Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM KlarTV.exe', '', SW_HIDE, ewWaitUntilTerminated, Code);
    Sleep(500);
  end;
  Result := '';
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
    DelTree(ExpandConstant('{localappdata}\Klar TV'), True, True, True);
end;
