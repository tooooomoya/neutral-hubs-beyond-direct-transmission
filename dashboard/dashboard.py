#!/usr/bin/env python3
"""Wrapper to execute the dashboard package module."""
import sys
from pathlib import Path

# Add the parent directory to sys.path so we can import the dashboard package
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from dashboard.__main__ import main

if __name__ == "__main__":
    main()
