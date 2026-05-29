import sys
from pathlib import Path

_DY_ROOT = Path(__file__).resolve().parent
# if str(_DY_ROOT) not in sys.path:
#     sys.path.insert(0, str(_DY_ROOT))

__version__ = "2.0.0"
