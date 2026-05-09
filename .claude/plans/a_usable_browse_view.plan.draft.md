
add to MediaEntryKind: Season/Episode/Series
add to MediaListItem: UserData/OriginalTitle/Genres/CommunityRating/Studios/ChildCount/Etag/IndexNumber

remove anything alread available in MediaListItem from MediaDetailModel
add to MediaDetailModel: BackdropImages/Images/Bitrate/ExternalUrls/ProductionYear/ProviderIds/MediaStreams/Etag

use &Fields= to reduce payload


CollectionFolder
Series -> Season -> Episode
Folder -> Movie

optimize browseScreen:
- if Movie or Folder with single child, navigate directly to detail
- on MediaEntryComponent: display CommunityRating and isfavorite (as a heart)

optimize detailScreen:
- use backdrop as background, not primary image
- place the control elements (play, favorite, add to list, overviews, logo) to the bottom of the screen, on top of the backdrop (z-axis)
- if movie, show play/resume button
- if series, show season list as a row of text (**Season 1** Season 2 Season 3), up/down to focus row, left/right to select season
- if series, show episode of current selected season as a row of thumbnails, with there episode number and title, up/down to focus row, left/right to select episode, enter to play/resume episode
- episode are ordered by indexNumber

layout demo:
------------------------------------
|                                  |
|                                  |
|                                  |
|             BACKDROP             |
| Logo                             |
| [rating][HEVC][DTS]              |
| [play][like][add to list]        |
| Overview                         |
|__________________________________|

------------------------------------
|                                  |
|             BACKDROP             |
| Logo                             |
| [rating][HEVC][DTS]              |
| [like][add to list]              |
| Overview                         |
| Season 1 Season 2 ...            |
| [e1][e2][e2]...                  |
|__________________________________|

add a titleLang configuration to appConfigStore, default = 'local', configurable to 'original', if 'original', show the original title instead of the localized title in all screens

add a setting button next to search for configuration