import React from 'react';
import axios from 'axios';
import ReactTable, {ReactTableDefaults} from 'react-table';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import ClickableIcon from "../common/ClickableIcon";

class FileExtensionList extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            currentList: [],
            showAddNew: false,
            newExtensionValue: "",
            newProxyType: "VIDEO"
        };

        this.columns = [
            {
                Header: "File extension",
                accessor: "extension"
            },
            {
                Header: "Proxy as",
                accessor: "proxyAs",
                Cell: props=><select onChange={()=>this.proxySelectionChanged(props.row[0], props.value)} value={props.value}>
                    <option value="VIDEO">Video</option>
                    <option value="AUDIO">Audio</option>
                    <option value="THUMBNAIL">Still image</option>
                    <option value="UNKNOWN">Ignore</option>
                </select>
            },
            {
                Header: "",
                accessor: "extension",
                Cell: props=><ClickableIcon onClick={()=>this.deleteExtension(props.value)} icon="trash"/>
            }
        ];

        this.newClicked = this.newClicked.bind(this);
    }

    componentWillMount() {
        this.updateData();
    }

    deleteExtension(extensionValue) {
        this.setState({loading: true, lastError:null}, ()=>axios.delete("/api/fileextension/" + extensionValue).then(response=>{
            const updatedList = this.state.contentList.filter(entry=>entry.extension!==extensionValue);
            this.setState({loading: false, lastError: null, contentList: updatedList});
        }))
    }

    proxySelectionChanged(extensionValue, newProxyValue) {
        console.log("Extension " + extensionValue + " value changed to " + newProxyValue);
        const dataToSend = {
            extension: extensionValue,
            proxyType: newProxyValue
        };
        this.setState({loading: true, lastError: null}, ()=>axios.put("/api/fileextension", dataToSend, {headers:{"Content-Type": "application/json"}}).then(response=>{
            const updatedList = this.state.currentList.filter(entry=>entry.extension!==extensionValue).concat(dataToSend);
            this.setState({loading: false, lastError:null, contentList: updatedList});
        }).catch(err=>{
            console.error(err);
            this.setState({lastError: err});
        }))
    }

    updateData(){
        this.setState({loading: true}, ()=>axios.get("/api/fileextension").then(result=>{
            this.setState({loading: false, lastError: null, currentList:result.data.entries});
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }))
    }

    newClicked(){
        this.setState({showAddNew: true});
    }

    saveNew(){
        const dataToSend = {
            extension: this.state.newExtensionValue,
            proxyType: this.state.newProxyType
        };
        this.setState({loading: true, lastError:null}, ()=>axios.put("/api/fileextension", dataToSend).then(response=>{
            this.setState({loading: false, currentList: [], showAddNew: false, newExtensionValue: ""}, ()=>this.updateData());
        }).catch(err=>{
            this.setState({lastError: err});
        }))
    }

    render() {
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            {
                this.state.lastError ? <ErrorViewComponent error={this.state.lastError}/> : <span/>;
            }
            <ReactTable data={this.state.currentList}
                        columns={this.columns}
                        column={Object.assign({}, ReactTableDefaults.column, {headerClassName: 'dashboardheader'})}
                        defaultSorted={[{
                            id: "extension",
                            desc: true
                        }]}/>
            <span style={{display: this.state.showAddNew ? "block" : "none"}}>
                <label htmlFor="new-extension-value">File extension</label>
                <input type="text" name="new-extension-value" onChange={evt=>this.setState({newExtensionValue: evt.target.value})} value={this.state.newExtensionValue}/>
                <select onChange={evt=>this.setState({newProxyType: evt.target.value})} value={this.state.newProxyType}>
                    <option value="VIDEO">Video</option>
                    <option value="AUDIO">Audio</option>
                    <option value="THUMBNAIL">Still image</option>
                    <option value="UNKNOWN">Ignore</option>
                </select>
                <ClickableIcon onClick={this.saveNew} icon="save"/>
            </span>
            <ClickableIcon onClick={this.newClicked} icon="plus"/>
        </div>
    }
}

export default FileExtensionList;