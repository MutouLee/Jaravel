package p;
public abstract class Base {
    protected Base() {
        // 生成的父类构造器中,能否初始化子类字段?
        tryInit();
    }
    protected abstract void tryInit();
}
