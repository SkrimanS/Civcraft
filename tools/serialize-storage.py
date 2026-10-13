from pathlib import Path
import re
path=Path('civcraft-leaf/src/main/java/com/avrgaming/civcraft/modern/storage/StorageBootstrap.java')
text=path.read_text(encoding='utf-8')
text=re.sub(r'^    public (?!synchronized |static |record |interface |class |StorageBootstrap\()(?=[^\n]+\([^\n]*\)\s*(?:throws [^\{]+)?\{)',
            '    public synchronized ',text,flags=re.M)
path.write_text(text,encoding='utf-8')
