import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import axios from 'axios';
import LoadingThrobber from "../common/LoadingThrobber.jsx";

class BulkSelectionsScroll extends React.Component {
    static propTypes = {
        entries: PropTypes.array.isRequired,
        currentSelection: PropTypes.string,
        onSelected: PropTypes.func,
        onDeleteClicked: PropTypes.func,
        forUser: PropTypes.string.isRequired,
        isAdmin: PropTypes.boolean
    };

    static nameExtractor = /^([^:]+):(.*)$/;

    constructor(props){
        super(props);

        this.state = {
            showRestoreSpinner: false
        };

        this.entryClicked = this.entryClicked.bind(this);
    }

    entryClicked(newId) {
        if(this.props.onSelected){
            this.props.onSelected(newId);
        }
    }

    extractNameAndPathArray(str) {
        const result = BulkSelectionsScroll.nameExtractor.exec(str);
        if(result){
            return ({name: result[1], pathArray: result[2].split("/")})
        } else {
            return ({name: str, pathArray: []})
        }
    }

    initiateDownloadInApp(entryId) {
        axios.get("/api/lightbox/bulk/appDownload/" + entryId, )
            .then(result=>{
                window.location.href = result.data.objectId;
            }).catch(err=>{
                console.error(err);
                this.setState({lastError: err});
        })
    }

    initiateRedoBulk(entryId) {
        axios.put("/api/lightbox/" + this.props.forUser + "/bulk/redoRestore/" + entryId).then(response=>{
            console.log(response.data);
            this.setState({showRestoreSpinner: false});
        }).catch(err=>{
            console.error(err);
            this.setState({showRestoreSpinner: false});
        })
    }

    render(){
        return <div className="bulk-selections-scroll">
            {
                this.props.entries.map((entry,idx)=>{
                    const bulkInfo = this.extractNameAndPathArray(entry.description);
                    const baseClasses = "entry-view bulk-selection-view clickable";
                    const classList = this.props.currentSelection === entry.id ? baseClasses + " entry-thumbnail-shadow" : baseClasses;

                    return <div className={classList} onClick={()=>this.props.onSelected(entry.id)}>
                        <p className="entry-title dont-expand"><FontAwesomeIcon style={{marginRight: "0.5em"}} icon="hdd"/>{bulkInfo.name}</p>
                        <p className="black small dont-expand deal-with-long-names"><FontAwesomeIcon style={{marginRight: "0.5em"}} icon="folder"/>{bulkInfo.pathArray.length>0 ? bulkInfo.pathArray.slice(-1) : ""}</p>
                        <p className="black small dont-expand"><FontAwesomeIcon style={{marginRight: "0.5em"}} icon="list-ol"/>{entry.availCount} items</p>
                        <div style={{overflow:"hidden", width:"100%"}}>
                            <a onClick={()=>{
                                this.initiateDownloadInApp(entry.id);
                                return false;
                            }} className="bulk-download-link">Download in app</a>

                            {this.props.isAdmin ?
                                <a onClick={() => {
                                    this.initiateRedoBulk(entry.id);
                                    return false
                                }} className="redo-restore-link">
                                    <LoadingThrobber show={this.state.showRestoreSpinner} small={true} inline={true}/>
                                    Redo restore of folder
                                </a> : ""
                            }

                        </div>
                        <FontAwesomeIcon icon="trash-alt" className="clickable" style={{color: "red", float: "right"}} onClick={evt=>{
                            evt.stopPropagation();
                            this.props.onDeleteClicked(entry.id);
                        }}/>
                        <p className="entry-date black">Added <TimestampFormatter relative={true} value={entry.addedAt}/></p>


                    </div>
                })
            }
        </div>
    }
}

export default BulkSelectionsScroll;