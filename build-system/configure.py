# This configure.py.template file is in the public domain.

from talerbuildconfig import *

b = BuildConfig()
b.enable_prefix()
b.enable_configmk()
b.add_tool(PosixTool("find"))
b.run()
