import React from 'react';
import axios from 'axios';
import PropTypes from 'prop-types';
import LoadingThrobber from "../../common/LoadingThrobber.jsx";
import SortableTable from "react-sortable-table";

class FindDeploymentComponent extends React.Component {
    static propTypes={
        shouldSearch: PropTypes.bool.isRequired,
        deploymentSelected: PropTypes.func.isRequired,
        currentSelectedDeployment: PropTypes.string.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            foundDeployments: [],
            regionErrors: [],
            lastError: null,
            loading: false,
            selectedDeployment: null
        };

        this.searchTableColumns = [
            {
                header: "Select",
                key: "stackId",
                render: value=><input type="radio"
                                      checked={this.props.currentSelectedDeployment===value}
                                      onChange={evt=>this.props.deploymentSelected(value)}/>
            },
            {
                header: "Stack Name",
                key: "stackName"
            },
            {
                header: "Description",
                key: "templateDescription"
            },
            {
                header: "Region",
                key: "region"
            },
            {
                header: "Status",
                key: "stackStatus"
            },
            {
                header: "Created",
                key: "creationTime"
            }
        ];

        this.style = {
            backgroundColor: '#eee',
            border: '1px solid black',
            borderCollapse: 'collapse'
        };

        this.iconStyle = {
            color: '#aaa',
            paddingLeft: '5px',
            paddingRight: '5px'
        };
    }

    componentWillMount(){
        if(this.props.shouldSearch){
            this.setState({loading: true, lastError: null}, ()=>axios.get("/api/proxyFramework/deploymentScan").then(response=>{
                this.setState({
                    loading: false,
                    lastError: null,
                    foundDeployments: response.data.success.reduce((acc,entry)=>acc.concat(entry), []),
                    regionErrors: response.data.failure.map(entry=>Object.assign({region: entry[0],error:entry[1]}))
                })
            }))
        }
    }

    renderSearchInterface(){
        return <div>
            <h3>Search for deployment</h3>
            <p></p>
            <SortableTable
                data={this.state.foundDeployments}
                columns={this.searchTableColumns}
                style={this.style}
                iconStyle={this.iconStyle}
                tableProps={{className: "dashboardpanel", display: this.state.foundDeployments.length>0 ? "block":"none"}}/>
            <LoadingThrobber show={this.state.loading} large={true} caption="Searching..."/>
            <div style={{display: this.state.regionErrors.length>0 ? "block" : "none"}}>
                <h4 style={{fontSize: "1.0em"}}>The following regions failed: </h4>
                <ul>
                    {
                        this.state.regionErrors.map(entry=><li key={entry.region}><b>{entry.region}</b>: {entry.error}</li>)
                    }
                </ul>
            </div>
        </div>
    }

    renderInputInterface(){
        return <div>not implemented</div>
    }
    render(){
        return this.props.shouldSearch ? this.renderSearchInterface() : this.renderInputInterface();
    }
}

export default FindDeploymentComponent;