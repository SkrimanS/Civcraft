from pathlib import Path

path = Path('civcraft-leaf/src/main/java/com/avrgaming/civcraft/modern/build/LegacyStructureService.java')
text = path.read_text(encoding='utf-8')
text = text.replace('"structures", definition.template()', 'definition.templateKind(), definition.template()')
path.write_text(text, encoding='utf-8')
