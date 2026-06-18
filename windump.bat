@echo off
if exist dump rd /s /q dump
mkdir dump
adb -s 25b41579 exec-out "run-as com.opentune.app sh -c 'cd cache && tar cz dump'" > dump.tar.gz && tar xzf dump.tar.gz -C dump
del dump.tar.gz