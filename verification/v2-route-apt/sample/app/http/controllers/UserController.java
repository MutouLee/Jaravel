package app.http.controllers;

public class UserController {
    public Object index()                        { return "all users"; }
    public Object show(Long id)                  { return "user " + id; }
    public Object store(Object body)             { return "created"; }
    public Object update(Long id, Object body)   { return "updated " + id; }
    public Object destroy(Long id)               { return "deleted " + id; }
}
