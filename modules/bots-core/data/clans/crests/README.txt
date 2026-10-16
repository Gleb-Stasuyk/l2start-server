Clan emblems for bot clans (Interlude): design 16x12 BMP.
On load they are padded to 16x16 (power-of-two) and encoded as DDS DXT1 (exactly 256 bytes)
for CrestTable.createCrest — same as the stock client upload / Living Worlds.
The bottom 4 rows are padding only; ally crest is a separate texture.
crestSeed in BotClans.xml is a 1-based index into the sorted *.bmp list (crest_001.bmp …).
Source pack may live in repo /crest; copy renamed files here for the module build.
