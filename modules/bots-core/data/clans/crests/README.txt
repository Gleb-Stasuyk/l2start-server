Clan emblems for bot clans (Interlude): design 16x12, 8-bit uncompressed BMP (Paint / 256 Color).
On load they are stretched to 16x16 (nearest-neighbour, like the client upload) and encoded as
DDS DXT1 (exactly 256 bytes, DDSCAPS=0) for CrestTable.createCrest.
crestSeed in BotClans.xml is a 1-based index into the sorted crest_NNN.bmp list.
Source pack: repo /crest (copy/rename into this folder for the module build).
