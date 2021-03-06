import React from 'react';
import {shallow,mount} from 'enzyme';
import EntryThumbnail from '../../app/Entry/EntryThumbnail.jsx';


describe("EntryThumbnail", ()=>{
    it("should return a video icon for a video MIME type",()=>{
        const rendered=mount(<EntryThumbnail mimeType={{major: "video", minor: "something"}} fileExtension="xxx" entryId="1234"/>);
        expect(rendered.find('FontAwesomeIcon').prop('icon')).toEqual("film");
    });

    it("should return an audio icon for an audio MIME type",()=>{
        const rendered=mount(<EntryThumbnail mimeType={{major: "audio", minor: "something"}} fileExtension="xxx" entryId="1234"/>);
        expect(rendered.find('FontAwesomeIcon').prop('icon')).toEqual("volume-up");
    });

    it("should return an image icon for an image MIME type",()=>{
        const rendered=mount(<EntryThumbnail mimeType={{major: "image", minor: "something"}} fileExtension="xxx" entryId="1234"/>);
        expect(rendered.find('FontAwesomeIcon').prop('icon')).toEqual("image");
    });

    it("should return a generic icon for any other MIME type",()=>{
        const rendered=mount(<EntryThumbnail mimeType={{major: "rhubarb", minor: "something"}} fileExtension="xxx" entryId="1234"/>);
        expect(rendered.find('FontAwesomeIcon').prop('icon')).toEqual("file");
    });

    it("should return a video icon for a video extension with octet-stream MIME type",()=>{
        const rendered=mount(<EntryThumbnail mimeType={{major: "application", minor: "octet-stream"}} fileExtension="mov" entryId="1234"/>);
        expect(rendered.find('FontAwesomeIcon').prop('icon')).toEqual("film");
    });

    it("should return an audio icon for an audio extension with octet-stream MIME type",()=>{
        const rendered=mount(<EntryThumbnail mimeType={{major: "application", minor: "octet-stream"}} fileExtension="WAV" entryId="1234"/>);
        expect(rendered.find('FontAwesomeIcon').prop('icon')).toEqual("volume-up");
    });

    it("should return an image icon for an image extension with octet-stream MIME type",()=>{
        const rendered=mount(<EntryThumbnail mimeType={{major: "application", minor: "octet-stream"}} fileExtension="jpg" entryId="1234"/>);
        expect(rendered.find('FontAwesomeIcon').prop('icon')).toEqual("image");
    });

    it("should return a generic icon for any other extension with octet-stream MIME type",()=>{
        const rendered=mount(<EntryThumbnail mimeType={{major: "application", minor: "octet-stream"}} fileExtension="xxx" entryId="1234"/>);
        expect(rendered.find('FontAwesomeIcon').prop('icon')).toEqual("file");
    });

    it("should not crash on a null file extension", ()=>{
        const rendered=mount(<EntryThumbnail mimeType={{major: "application", minor: "octet-stream"}} fileExtension={null} entryId="1234"/>);
        expect(rendered.find('FontAwesomeIcon').prop('icon')).toEqual("file");
    });

    it("should not crash on a null mimeType", ()=>{
        const rendered=mount(<EntryThumbnail mimeType={null} fileExtension="xxx" entryId="1234"/>);
        expect(rendered.find('FontAwesomeIcon').prop('icon')).toEqual("file");
    })
});