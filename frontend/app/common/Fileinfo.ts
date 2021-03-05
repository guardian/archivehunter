
interface FileInfo {
    filename: string;
    filepath: string;
}

function extractFileInfo (fullpath:string):FileInfo {
    const parts = fullpath.split("/");
    const len = parts.length;
    if(len===0){
        return {
            filename: parts[0],
            filepath: ""
        }
    }

    return {
        filename: parts[len-1],
        filepath: parts.slice(0,len-1).join("/")
    }
}

export type {FileInfo};
export {extractFileInfo};