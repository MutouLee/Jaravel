package io.jaravel.benchmark;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;

/** JPA 侧的被测实体。字段与 JaravelPost 一一对应。 */
@Entity
@Table(name = "bench_posts")
public class JpaPost {

    @jakarta.persistence.Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String body;

    @Column(nullable = false)
    private int views;

    public Long getId()            { return id; }
    public void setId(Long id)     { this.id = id; }
    public String getTitle()       { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getBody()        { return body; }
    public void setBody(String b)  { this.body = b; }
    public int getViews()          { return views; }
    public void setViews(int v)    { this.views = v; }
}
