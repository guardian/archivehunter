import {baseName} from "../../app/common/Fileinfo";

describe("baseName", ()=>{
    it("should return only the filename portion of an absolute path", ()=>{
        const result = baseName("/path/to/some/file.ext");
        expect(result).toEqual("file.ext");
    });

    it("should return only the filename portion of a relative path", ()=>{
        const result = baseName("to/some/file.ext");
        expect(result).toEqual("file.ext");
    });

    it("should return a bare filename unchanged", ()=>{
        const result = baseName("file.ext");
        expect(result).toEqual("file.ext");
    });

    it("should return undefined if passed undefined", ()=>{
        expect(baseName(undefined)).toBeUndefined();
    })
})