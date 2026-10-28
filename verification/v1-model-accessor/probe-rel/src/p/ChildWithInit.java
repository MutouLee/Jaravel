package p;
public class ChildWithInit extends Base {
    public String rel = null;          // 用户写了显式初始化器
    @Override protected void tryInit() { this.rel = "INITIALIZED"; }
}
