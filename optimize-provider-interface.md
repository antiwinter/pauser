1. remove EntryDetail and getDetail. merge into EntryInfo

EntryInfo should also include: logo/backdrop/bitrate/year/durationMs/width/height/officialRating/collectionType/filename

remove EntryType enum, use string instead 
'Folder', 'Season' ->  browse
'Movie', 'Digipak', 'Series' ->  overview
'Episode', 'Video' ->  player
'Image' -> image viewer
'Audio' -> audio unsupported screen (AUS)
'Unknown' -> filename detection -> image viewer, player, AUS

smb provider should only provide filename, don't do filename detection.

filename detection should be central, entries other then 'Video'/'Audio'/'Image' should be hide
better to use a lib for filename detection, instead of write manually


2. simplify playbackSpec
remove title/bitrate/durationMs from playSpec
add media codec info: [{codec: 'hevc', BitDepth: 10}, {codec: 'dts'}, {codec: 'ac3'}]. info[0].codec always contain video codec infomation, which will be shown in detail screen
simplify hookstate, url/header seems overlap with hooksState, make single source truth

check if mime is needed

make clear the proxy logic

3. revise detailScreen flow:
make 3 kinds of detail screen:
Movie -> movieOverview
Series -> seriesOverview
Digipak -> digipackOverview
reuse common component, design different layouts

pass ItemInfo into detail screen, so no need to fetch information again (getDetail is removed)

resolve user progress (e.g. which season which episode user is on), getPlaybackSpec in detailScreen & start buffering (player hidden but buffering), so the user feels player start immediately when they press a media

the detail screen should show: 
page1: backdrop1, played/year/communityRating/duration/resolution(SD/HD/FHD/QHD/WQHD/4K/5K/8K)/bitDepth/Video codec/Audio codec/officialRating/genres/Overview(snippet)
page2: backdrop 2, full overview, cast & crew
left/right to pushin page1 & page2
show page indicators

4. update browse methodology:
if browse a view, whose collection type is movies, use {recursive, type = 'Movie'} to list only movies
