@echo off
if exist dump rd /s /q dump
mkdir dump
adb -s 25b41579 exec-out "run-as com.opentune.app sh -c 'cd cache && tar cz .'" > dump.tar.gz || (echo adb failed & del dump.tar.gz & exit /b 1)
tar xzf dump.tar.gz -C dump || (echo tar extract failed & del dump.tar.gz & exit /b 1)
del dump.tar.gz