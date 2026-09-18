@echo off
rem 重新生成 ble_service.exe（PyInstaller 打包，随应用分发，目标机器无需安装 Python/bleak）
rem 产物输出到 shared\build\generated\ble-exe（gradle 自动挂进打包资源，不进git）
rem 修改 shared\src\jvmMain\resources\ble_service.py 后重新构建即可，gradle 检测变化自动重打包
cd /d %~dp0..

pip install bleak pyinstaller || goto :err
pyinstaller --onefile --name ble_service ^
  --collect-all bleak --collect-submodules winrt ^
  --distpath shared\build\generated\ble-exe ^
  --workpath build\pyinstaller --specpath build\pyinstaller ^
  --clean --noconfirm ^
  shared\src\jvmMain\resources\ble_service.py || goto :err

echo Done: shared\build\generated\ble-exe\ble_service.exe
goto :eof

:err
echo FAILED
exit /b 1
