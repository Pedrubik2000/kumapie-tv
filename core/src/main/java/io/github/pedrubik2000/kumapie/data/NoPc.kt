package io.github.pedrubik2000.kumapie.data

import io.github.pedrubik2000.kumapie.i18n.tr
import java.io.IOException

class NoPc(path: String) : IOException(tr("No PC for %s", path))
