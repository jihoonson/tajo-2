package org.apache.tajo.org.python.core;

import org.apache.tajo.org.python.antlr.base.mod;

public interface PythonCompiler {

    PythonCodeBundle compile(mod node, String name, String filename,
                             boolean linenumbers, boolean printResults, CompilerFlags cflags)
            throws Exception;

}
