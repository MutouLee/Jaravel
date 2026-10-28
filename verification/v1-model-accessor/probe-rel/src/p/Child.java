package p;
public class Child extends Base {
    public String rel;                 // 模拟 public Rel<Post> posts;
    @Override protected void tryInit() { this.rel = "INITIALIZED"; }
}
