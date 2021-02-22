import React from 'react';
import {extractFileInfo} from "../../app/Entry/details/MetadataTable";

describe("MetadataEntry.extractFileInfo", ()=>{
    it("should seperate a full path into path and filename", ()=>{
        const result = extractFileInfo("path/to/some/file.ext");
        console.log(result);
        expect(result.filename).toEqual("file.ext");
        expect(result.filepath).toEqual("path/to/some");
    });
});