@echo off
if exist dump rd /s /q dump
mkdir dump
adb -s 25b41579 exec-out "run-as com.opentune.app sh -c 'cd cache && tar cz emby_dump'" > emby_dump.tar.gz && tar xzf emby_dump.tar.gz -C dump
del emby_dump.tar.gz