"""Validate every explicitly allocated listening endpoint before an owned rig starts."""

def bindings(runtime,parse):
    extra=runtime.get('additional_bind_endpoints',[])
    if not isinstance(extra,list) or any(not isinstance(value,str) for value in extra):
        raise ValueError('additional bind endpoints must be a list of strings')
    values=[runtime['bind_endpoint'],*extra]
    parsed=[parse(value) for value in values]
    if len(parsed)!=len(set(parsed)):raise ValueError('duplicate bind endpoint')
    return values

def check_available(runtime,parse,probe):
    for value in bindings(runtime,parse):probe(value)
