
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

const baseNameXtractor = /\/([^\/]+)$/;
const baseName = (str:string|undefined) => {
    if(str && str.length>0) {
        const result = baseNameXtractor.exec(str);
        if(result) {
            return result[1];
        } else if(!str.includes("/")) {
            return str;
        } else {
            throw `baseName extraction failed on ${str}, please fix`;
        }
    } else {
        return undefined
    }
}

export type {FileInfo};
export {extractFileInfo, baseName};