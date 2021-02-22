
function urlParamsFromSearch(search:string):Record<string, string> {
    if(!search) return {};

    const searchToUse = search.startsWith("?") ? search.slice(1) : search;

    const elems = searchToUse.split("&");
    let result:Record<string,string> = {};

    if(searchToUse=="") return {};
    for(let i=0;i<elems.length;++i) {
        const kv = elems[i].split("=")
        result[kv[0]] = decodeURIComponent(kv[1]);
    }
    return result;
}

export {urlParamsFromSearch};