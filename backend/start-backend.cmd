@echo off
rem Starts the Stationary Shop backend (and the website it serves) on http://localhost:8080/
rem Run it by double-clicking, or from Command Prompt:  backend\start-backend.cmd
rem NOTE: this helper has not been tested on Windows by the author of the project files.
if "%DB_URL%"=="" set DB_URL=jdbc:postgresql://localhost:5432/stationary_shop
if "%DB_USERNAME%"=="" set DB_USERNAME=postgres
if "%DB_PASSWORD%"=="" set /p DB_PASSWORD=Enter your PostgreSQL password (it is typed visibly and not saved): 
cd /d "%~dp0"
call mvn spring-boot:run
pause
