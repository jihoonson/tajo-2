// Autogenerated AST node
package org.apache.tajo.org.python.antlr.op;

import org.apache.tajo.org.python.antlr.AST;
import org.apache.tajo.org.python.antlr.PythonTree;
import org.apache.tajo.org.python.core.Py;
import org.apache.tajo.org.python.core.PyObject;
import org.apache.tajo.org.python.core.PyString;
import org.apache.tajo.org.python.core.PyType;
import org.apache.tajo.org.python.expose.ExposedGet;
import org.apache.tajo.org.python.expose.ExposedMethod;
import org.apache.tajo.org.python.expose.ExposedNew;
import org.apache.tajo.org.python.expose.ExposedType;

@ExposedType(name = "_ast.Param", base = AST.class)
public class Param extends PythonTree {
    public static final PyType TYPE = PyType.fromClass(Param.class);

public Param() {
}

public Param(PyType subType) {
    super(subType);
}

@ExposedNew
@ExposedMethod
public void Param___init__(PyObject[] args, String[] keywords) {}

    private final static PyString[] fields = new PyString[0];
    @ExposedGet(name = "_fields")
    public PyString[] get_fields() { return fields; }

    private final static PyString[] attributes = new PyString[0];
    @ExposedGet(name = "_attributes")
    public PyString[] get_attributes() { return attributes; }

    @ExposedMethod
    public PyObject __int__() {
        return Param___int__();
    }

    final PyObject Param___int__() {
        return Py.newInteger(6);
    }

}
