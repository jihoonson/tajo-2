// Autogenerated AST node
package org.apache.tajo.org.python.antlr.ast;

import org.antlr.runtime.Token;
import org.apache.tajo.org.python.antlr.AST;
import org.apache.tajo.org.python.antlr.PythonTree;
import org.apache.tajo.org.python.antlr.adapter.AstAdapters;
import org.apache.tajo.org.python.antlr.base.expr;
import org.apache.tajo.org.python.core.*;
import org.apache.tajo.org.python.expose.*;

@ExposedType(name = "_ast.Attribute", base = AST.class)
public class Attribute extends expr implements Context {
public static final PyType TYPE = PyType.fromClass(Attribute.class);
    private expr value;
    public expr getInternalValue() {
        return value;
    }
    @ExposedGet(name = "value")
    public PyObject getValue() {
        return value;
    }
    @ExposedSet(name = "value")
    public void setValue(PyObject value) {
        this.value = AstAdapters.py2expr(value);
    }

    private String attr;
    public String getInternalAttr() {
        return attr;
    }
    private Name attrName;
    public Name getInternalAttrName() {
        return attrName;
    }
    @ExposedGet(name = "attr")
    public PyObject getAttr() {
        if (attr == null) return Py.None;
        return new PyString(attr);
    }
    @ExposedSet(name = "attr")
    public void setAttr(PyObject attr) {
        this.attr = AstAdapters.py2identifier(attr);
    }

    private expr_contextType ctx;
    public expr_contextType getInternalCtx() {
        return ctx;
    }
    @ExposedGet(name = "ctx")
    public PyObject getCtx() {
        return AstAdapters.expr_context2py(ctx);
    }
    @ExposedSet(name = "ctx")
    public void setCtx(PyObject ctx) {
        this.ctx = AstAdapters.py2expr_context(ctx);
    }


    private final static PyString[] fields =
    new PyString[] {new PyString("value"), new PyString("attr"), new PyString("ctx")};
    @ExposedGet(name = "_fields")
    public PyString[] get_fields() { return fields; }

    private final static PyString[] attributes =
    new PyString[] {new PyString("lineno"), new PyString("col_offset")};
    @ExposedGet(name = "_attributes")
    public PyString[] get_attributes() { return attributes; }

    public Attribute(PyType subType) {
        super(subType);
    }
    public Attribute() {
        this(TYPE);
    }
    @ExposedNew
    @ExposedMethod
    public void Attribute___init__(PyObject[] args, String[] keywords) {
        ArgParser ap = new ArgParser("Attribute", args, keywords, new String[]
            {"value", "attr", "ctx", "lineno", "col_offset"}, 3, true);
        setValue(ap.getPyObject(0, Py.None));
        setAttr(ap.getPyObject(1, Py.None));
        setCtx(ap.getPyObject(2, Py.None));
        int lin = ap.getInt(3, -1);
        if (lin != -1) {
            setLineno(lin);
        }

        int col = ap.getInt(4, -1);
        if (col != -1) {
            setLineno(col);
        }

    }

    public Attribute(PyObject value, PyObject attr, PyObject ctx) {
        setValue(value);
        setAttr(attr);
        setCtx(ctx);
    }

    public Attribute(Token token, expr value, String attr, expr_contextType ctx) {
        super(token);
        this.value = value;
        addChild(value);
        this.attr = attr;
        this.ctx = ctx;
    }

    public Attribute(Token token, expr value, Name attr, expr_contextType ctx) {
        super(token);
        this.value = value;
        addChild(value);
        this.attr = attr.getText();
        this.attrName = attr;
        this.ctx = ctx;
    }

    public Attribute(Integer ttype, Token token, expr value, Name attr, expr_contextType ctx) {
        super(ttype, token);
        this.value = value;
        addChild(value);
        this.attr = attr.getText();
        this.attrName = attr;
        this.ctx = ctx;
    }

    public Attribute(Integer ttype, Token token, expr value, String attr, expr_contextType ctx) {
        super(ttype, token);
        this.value = value;
        addChild(value);
        this.attr = attr;
        this.ctx = ctx;
    }

    public Attribute(PythonTree tree, expr value, String attr, expr_contextType ctx) {
        super(tree);
        this.value = value;
        addChild(value);
        this.attr = attr;
        this.ctx = ctx;
    }

    @ExposedGet(name = "repr")
    public String toString() {
        return "Attribute";
    }

    public String toStringTree() {
        StringBuffer sb = new StringBuffer("Attribute(");
        sb.append("value=");
        sb.append(dumpThis(value));
        sb.append(",");
        sb.append("attr=");
        sb.append(dumpThis(attr));
        sb.append(",");
        sb.append("ctx=");
        sb.append(dumpThis(ctx));
        sb.append(",");
        sb.append(")");
        return sb.toString();
    }

    public <R> R accept(VisitorIF<R> visitor) throws Exception {
        return visitor.visitAttribute(this);
    }

    public void traverse(VisitorIF<?> visitor) throws Exception {
        if (value != null)
            value.accept(visitor);
    }

    public void setContext(expr_contextType c) {
        this.ctx = c;
    }

    private int lineno = -1;
    @ExposedGet(name = "lineno")
    public int getLineno() {
        if (lineno != -1) {
            return lineno;
        }
        return getLine();
    }

    @ExposedSet(name = "lineno")
    public void setLineno(int num) {
        lineno = num;
    }

    private int col_offset = -1;
    @ExposedGet(name = "col_offset")
    public int getCol_offset() {
        if (col_offset != -1) {
            return col_offset;
        }
        return getCharPositionInLine();
    }

    @ExposedSet(name = "col_offset")
    public void setCol_offset(int num) {
        col_offset = num;
    }

}
