'Get Java path.
Dim path
Set shell = WScript.CreateObject("WScript.Shell")
path = shell.Environment.Item("JAVA_HOME")
If path = "" Then
	MsgBox "Could not find JAVA_HOME environment variable!", vbOKOnly, "Database Installer"
Else
	If InStr(path, "\bin") = 0 Then
		path = path + "\bin\"
	Else
		path = path + "\"
	End If
	path = Replace(path, "\\", "\")
	path = Replace(path, "Program Files", "Progra~1")
End If

'Load GUI configuration.
window = 1
Set file = CreateObject("Scripting.FileSystemObject").OpenTextFile("config\Interface.ini", 1)
Do While Not file.AtEndOfStream
	If Replace(LCase(file.ReadLine()), " ", "") = "enablegui=true" Then
		window = 0
		Exit Do
	End If
Loop
file.Close
Set file = Nothing

'Generate command.
command = path & "java -jar DatabaseInstaller.jar"
If window = 1 Then
	command = "cmd /c start ""L2J Mobius - Database Installer"" " & command
End If

'Run the Database Installer.
exitcode = shell.Run(command, window, True)
If exitcode <> 0 Then
	hint = ""
	Select Case exitcode
		Case 1
			hint = "Uncaught Java exception or wrong Java version."
		Case 130
			hint = "Interrupted by Ctrl+C."
		Case 137
			hint = "Killed by the OS (out of memory?)."
		Case 143
			hint = "Terminated by SIGTERM."
	End Select
	If hint = "" And exitcode < 0 Then
		hint = "JVM or native crash (access violation)."
	End If
	msg = "Database Installer terminated abnormally!" & vbCrLf & vbCrLf & "Exit code: " & exitcode
	If hint <> "" Then
		msg = msg & "  (" & hint & ")"
	End If
	MsgBox msg, vbOKOnly + vbExclamation, "Database Installer"
End If
