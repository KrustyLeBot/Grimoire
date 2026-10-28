import os
import sys
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
FIXTURES = Path(__file__).resolve().parent.parent.parent / "app" / "src" / "test" / "resources"
