import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";

class BulkSelectionsScroll extends React.Component {
    static propTypes = {
        entries: PropTypes.array.isRequired,
        currentSelection: PropTypes.string,
        onSelected: PropTypes.func,
        onDeleteClicked: PropTypes.func
    };

    static nameExtractor = /^([^:]+):(.*)$/;

    constructor(props){
        super(props);

        this.entryClicked = this.entryClicked.bind(this);
    }

    entryClicked(newId) {
        if(this.props.onSelected){
            this.props.onSelected(newId);
        }
    }

    extractNameAndPathArray(str) {
        const result = BulkSelectionsScroll.nameExtractor.exec(str);
        console.log(str, result);
        if(result){
            return ({name: result[1], pathArray: result[2].split("/")})
        } else {
            return ({name: str, pathArray: []})
        }
    }

    render(){
        return <div className="bulk-selections-scroll">
            {
                this.props.entries.map((entry,idx)=>{
                    const bulkInfo = this.extractNameAndPathArray(entry.description);
                    //console.log(bulkInfo);
                    const baseClasses = "entry-view half-height clickable";
                    const classList = this.props.currentSelection === entry.id ? baseClasses + " entry-thumbnail-shadow" : baseClasses;

                    return <div className={classList} onClick={()=>this.props.onSelected(entry.id)}>
                        <p className="entry-title dont-expand"><FontAwesomeIcon style={{marginRight: "0.5em"}} icon="hdd"/>{bulkInfo.name}</p>
                        <p className="black small dont-expand"><FontAwesomeIcon style={{marginRight: "0.5em"}} icon="folder"/>{bulkInfo.pathArray.length>0 ? bulkInfo.pathArray.slice(-1) : ""}</p>
                        <p className="black small dont-expand"><FontAwesomeIcon style={{marginRight: "0.5em"}} icon="list-ol"/>{entry.availCount} items</p>
                        <FontAwesomeIcon icon="trash-alt" className="clickable" style={{color: "red", float: "right"}} onClick={()=>this.props.onDeleteClicked(entry.id)}/>
                        <p className="entry-date black">Added <TimestampFormatter relative={true}
                                                                      value={entry.addedAt}/></p>

                    </div>
                })
            }
        </div>
    }
}

export default BulkSelectionsScroll;