import React from 'react';
import {shallow,mount} from 'enzyme';
import EntryDetails from '../../app/Entry/EntryDetails.jsx';
import {BrowserRouter} from 'react-router-dom';

describe("EntryDetails.extractFileInfo", ()=>{
    it("should seperate a full path into path and filename", ()=>{
        const d = new EntryDetails;

        const result = d.extractFileInfo("path/to/some/file.ext");
        console.log(result);
        expect(result.filename).toEqual("file.ext");
        expect(result.filepath).toEqual("path/to/some");
    });
});