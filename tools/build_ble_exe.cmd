@echo off
rem 重新生成 ble_service.exe（PyInstaller 打包，随应用分发，目标机器无需安装 Python/bleak）
rem 修改 shared\src\jvmMain\resources\ble_service.py 后执行本脚本重新生成 exe
cd /d %~dp0..

pip install bleak pyinstaller || goto :err
pyinstaller --onefile --name ble_service ^
  --collect-all bleak --collect-submodules winrt ^
  --distpath shared\src\jvmMain\resources ^
  --workpath build\pyinstaller --specpath build\pyinstaller ^
  --clean --noconfirm ^
  shared\src\jvmMain\resources\ble_service.py || goto :err

echo Done: shared\src\jvmMain\resources\ble_service.exe
goto :eof

:err
echo FAILED
exit /b 1
